# End-to-end load + verify test

`outbox-load-test.js` drives the full running stack — `rest-service` → outbox table → Debezium
CDC → Kafka → `email-service`'s rate-limited consumer — through its public HTTP surface only,
and asserts the pattern actually works end-to-end, including that the downstream rate limiter
visibly throttles delivery.

It is **not** part of the Kind cluster's own manifests; run it manually with the k6 CLI against
an already-running cluster.

## Prerequisites

- [k6](https://k6.io/docs/get-started/installation/) installed locally.
- The Kind cluster up and healthy: `./up.sh` from the repo root (see the top-level README).

## Run it

`rest-service` and `email-service` are reachable via Kind's NodePorts, and `PROMETHEUS_URL`
is required to get a reliable read on `emails.sent` (see "Why `PROMETHEUS_URL`" below):

```sh
NODE_IP=$(docker inspect outbox-worker --format '{{.NetworkSettings.Networks.kind.IPAddress}}')
k6 run \
  -e REST_SERVICE_URL=http://$NODE_IP:30081 \
  -e EMAIL_SERVICE_URL=http://$NODE_IP:30082 \
  -e PROMETHEUS_URL=http://$NODE_IP:30390 \
  k6/outbox-load-test.js
```

This posts 20 orders to `rest-service` over ~10 seconds (well above the 5-per-10s rate limit),
asserts every response was `201`, then polls `emails.sent` (summed across `email-service`'s
replicas via Prometheus) until it reaches 20, asserting that took at least as long as the rate
limiter mandates.

A passing run means: every order was created, every order's confirmation email was eventually
sent (no drops), and delivery was actually throttled rather than instantaneous.

`emails.sent` is a stack-wide counter, so the verify stage's target (`baseline + ORDER_COUNT`)
can only be trusted if nothing else is still feeding it emails from an earlier invocation. Run
this against a freshly-started cluster, and if a previous run of the script timed out or was
interrupted partway through, do a full reset — `./down.sh && ./up.sh` from the repo root —
before running it again. That wipes the whole Kind cluster, Postgres data included, so there's
no risk of stale `outbox` rows causing Debezium to re-emit a backlog on top of what's already
there (unlike a partial reset that leaves the data volume in place).

## Why `PROMETHEUS_URL`

`email-service` runs 2 replicas behind a single-partition topic, so only one replica's JVM ever
holds a non-zero `emails.sent` counter — polling `EMAIL_SERVICE_URL` directly (a load-balanced
Service) has roughly even odds of landing on the idle replica for the whole test and failing
even though the pipeline is healthy. `PROMETHEUS_URL` points the script at the cluster's
Prometheus (which scrapes both replicas, see `k8s/monitoring/01-prometheus.yaml`) so it sums
`emails.sent` across replicas via PromQL instead — correct regardless of which pod is consuming.

With `PROMETHEUS_URL` set, `EMAIL_SERVICE_URL` is never polled for the `emails.sent` count (see
`emailsSentCount()` in the script), so any node's IP works for it. Without `PROMETHEUS_URL`,
`EMAIL_SERVICE_URL` must point at a node that's actually running an `email-service` pod — its
Service uses `externalTrafficPolicy: Local`, so a node without one doesn't fail fast, the
connection just hangs until it times out. Check which nodes have a pod first (`kubectl get pods
-n kafka -o wide -l app=email-service`) if you need to fall back to this.

## Configuration

All parameters are overridable via environment variables (`k6 run -e NAME=value ...`):

| Variable | Default | Meaning |
| --- | --- | --- |
| `REST_SERVICE_URL` | `http://localhost:8081` | Base URL of `rest-service` |
| `EMAIL_SERVICE_URL` | `http://localhost:8082` | Base URL of `email-service` |
| `PROMETHEUS_URL` | *(unset)* | If set, poll `emails.sent` by summing it across replicas via PromQL instead of hitting `EMAIL_SERVICE_URL` directly — see "Why `PROMETHEUS_URL`" above |
| `ORDER_COUNT` | `20` | Orders to post in the load stage |
| `LOAD_DURATION_SECONDS` | `10` | Time to spread those posts over |
| `RATE_LIMIT_FOR_PERIOD` | `5` | Must match `email-service`'s configured rate limit |
| `RATE_LIMIT_PERIOD_SECONDS` | `10` | Must match `email-service`'s configured rate limit |
| `POLL_INTERVAL_SECONDS` | `1` | How often to poll `emails.sent` in the verify stage |
| `POLL_TIMEOUT_SECONDS` | `90` | Max time to wait for `emails.sent` to reach the target |
| `MIN_DURATION_SAFETY_FACTOR` | `0.6` | Fraction of the theoretical minimum throttled duration required to pass — see comment in the script |

## Verifying the test actually exercises throttling

To confirm the timing assertion is meaningful rather than a tautology, temporarily raise
`email-service`'s rate limiter (e.g. bump `limitForPeriod` from `5` to something large like
`1000` in `ConfirmationService`, or otherwise defeat it), then rebuild and redeploy it:

```sh
docker build --network host -t local/email-service:latest ./email-service
kind load docker-image --name outbox local/email-service:latest
kubectl rollout restart deployment/email-service -n kafka
kubectl rollout status deployment/email-service -n kafka
```

(`imagePullPolicy: Never` plus an unchanged `:latest` tag means `kubectl apply` alone won't pick
up a rebuilt image — the explicit `rollout restart` is what forces the pods to restart against
it.) Re-run the script — the final `check` (`delivery took at least as long as the rate limiter
mandates`) should now fail, since delivery will complete almost immediately instead of over
several rate-limit windows. Revert the change and repeat the rebuild/redeploy afterwards.
