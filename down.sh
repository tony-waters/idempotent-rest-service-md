#!/usr/bin/env bash
# Tears down the complete Kind HA stack brought up by up.sh: deletes the whole Kind cluster,
# wiping all state (Postgres data included) along with it. Safe to re-run if already down.
set -euo pipefail

CLUSTER_NAME="outbox"

command -v kind >/dev/null 2>&1 || { echo "error: 'kind' is required but not on PATH" >&2; exit 1; }

if kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME"; then
  kind delete cluster --name "$CLUSTER_NAME"
else
  echo "cluster '$CLUSTER_NAME' does not exist, nothing to do"
fi
