#!/usr/bin/env bash
# Brings up the complete Kind HA stack (see README.md "Running on Kind"): the Kind cluster,
# Strimzi + Prometheus operators, all built/loaded images, and every manifest under k8s/.
# Safe to re-run: each step no-ops (rather than fails) if it's already done.
set -euo pipefail

CLUSTER_NAME="outbox"
STRIMZI_VERSION="1.2.0"
PROMETHEUS_OPERATOR_VERSION="v0.94.0"

for bin in kind kubectl docker; do
  command -v "$bin" >/dev/null 2>&1 || { echo "error: '$bin' is required but not on PATH" >&2; exit 1; }
done

echo "==> Kind cluster"
if kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME"; then
  echo "cluster '$CLUSTER_NAME' already exists, skipping create"
else
  kind create cluster --name "$CLUSTER_NAME" --config kind-config.yaml
fi
kubectl wait --for=condition=Ready nodes --all --timeout=120s

# Loads one image into every node's containerd. Falls back to `docker save` + `ctr images
# import` (skipping --all-platforms) when `kind load docker-image` hits the known
# multi-platform-manifest bug (content digest ... not found) — see README's Kind section.
load_image() {
  local image="$1"
  if kind load docker-image --name "$CLUSTER_NAME" "$image"; then
    return
  fi
  echo "kind load docker-image failed for $image, falling back to per-node ctr import"
  local tar="/tmp/kind-load-$$.tar"
  docker save "$image" -o "$tar"
  for node in "${CLUSTER_NAME}-control-plane" "${CLUSTER_NAME}-worker" "${CLUSTER_NAME}-worker2" "${CLUSTER_NAME}-worker3"; do
    docker cp "$tar" "$node":/image.tar
    docker exec "$node" ctr --namespace=k8s.io images import /image.tar
    docker exec "$node" rm -f /image.tar
  done
  rm -f "$tar"
}

echo "==> Build and load images"
docker build --network host -t local/rest-service:latest ./rest-service
docker build --network host -t local/email-service:latest ./email-service
docker build -f connect/Dockerfile.strimzi -t "local/strimzi-connect-debezium:${STRIMZI_VERSION}" connect/

docker pull "quay.io/strimzi/operator:${STRIMZI_VERSION}"
docker pull "quay.io/strimzi/kafka:${STRIMZI_VERSION}-kafka-4.3.1"
docker pull postgres:16
docker pull provectuslabs/kafka-ui:v0.7.2
docker pull grafana/grafana:11.3.1
docker pull grafana/tempo:2.6.1
docker pull quay.io/prometheus/prometheus:v3.14.0
docker pull "quay.io/prometheus-operator/prometheus-operator:${PROMETHEUS_OPERATOR_VERSION}"
docker pull "quay.io/prometheus-operator/prometheus-config-reloader:${PROMETHEUS_OPERATOR_VERSION}"

for image in \
  local/rest-service:latest \
  local/email-service:latest \
  "local/strimzi-connect-debezium:${STRIMZI_VERSION}" \
  "quay.io/strimzi/operator:${STRIMZI_VERSION}" \
  "quay.io/strimzi/kafka:${STRIMZI_VERSION}-kafka-4.3.1" \
  postgres:16 \
  provectuslabs/kafka-ui:v0.7.2 \
  grafana/grafana:11.3.1 \
  grafana/tempo:2.6.1 \
  quay.io/prometheus/prometheus:v3.14.0 \
  "quay.io/prometheus-operator/prometheus-operator:${PROMETHEUS_OPERATOR_VERSION}" \
  "quay.io/prometheus-operator/prometheus-config-reloader:${PROMETHEUS_OPERATOR_VERSION}"
do
  load_image "$image"
done

echo "==> Strimzi operator"
kubectl create namespace kafka --dry-run=client -o yaml | kubectl apply -f -
curl -sL "https://github.com/strimzi/strimzi-kafka-operator/releases/download/${STRIMZI_VERSION}/strimzi-cluster-operator-${STRIMZI_VERSION}.yaml" \
  | sed 's/namespace: .*/namespace: kafka/' \
  | kubectl apply -f - -n kafka
kubectl wait --for=condition=Available deployment/strimzi-cluster-operator -n kafka --timeout=6000s

echo "==> Prometheus Operator"
kubectl create namespace monitoring --dry-run=client -o yaml | kubectl apply -f -
curl -sL "https://github.com/prometheus-operator/prometheus-operator/releases/download/${PROMETHEUS_OPERATOR_VERSION}/bundle.yaml" \
  | sed 's/namespace: default/namespace: monitoring/' \
  | kubectl apply --server-side -f -
kubectl wait --for=condition=Available deployment/prometheus-operator -n monitoring --timeout=180s

echo "==> Apply manifests"
kubectl apply -f k8s/
kubectl apply -f k8s/monitoring/

echo "==> Waiting for everything to come up"
kubectl wait kafka/outbox -n kafka --for=condition=Ready --timeout=300s
kubectl wait kafkaconnect/outbox-connect -n kafka --for=condition=Ready --timeout=180s
kubectl rollout status deployment/rest-service -n kafka
kubectl rollout status deployment/email-service -n kafka
kubectl rollout status deployment/grafana -n monitoring
kubectl rollout status deployment/tempo -n monitoring
kubectl get kafkaconnector -n kafka

NODE_IP=$(docker inspect "${CLUSTER_NAME}-worker" --format '{{.NetworkSettings.Networks.kind.IPAddress}}')
cat <<EOF

==> Stack is up.
  rest-service:   http://${NODE_IP}:30081
  email-service:  http://${NODE_IP}:30082
  kafka-ui:       http://${NODE_IP}:30080
  grafana:        http://${NODE_IP}:30300
  prometheus:     http://${NODE_IP}:30390

Run ./down.sh to tear it all down.
EOF
