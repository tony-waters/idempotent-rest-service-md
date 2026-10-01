// Demonstrates the Idempotency-Key mechanism on POST /orders (ADR 0006, IdempotencyAspect +
// IdempotencyStore), end-to-end through rest-service's public HTTP surface only.
//
// Run this manually via the k6 CLI against an already-running cluster (see ./up.sh and
// README.md) — it is not part of the cluster's own manifests. Makes no assertions against
// Redis/Postgres directly; every check is on the HTTP response.
//
// What it proves, in two scenarios:
//   1. idempotency_demo (sequential, single VU): a fresh key creates an order (201); replaying
//      the same key with the same body returns the *same* order instead of a new one; reusing
//      the key with a different body is rejected (409 Conflict); a request with no
//      Idempotency-Key header at all is rejected (400 Bad Request).
//   2. concurrent_lock_demo (CONCURRENT_VUS VUs, all racing one pre-shared key at once): exactly
//      one request wins the lock and gets 201, every other concurrent request is rejected with
//      425 Too Early — demonstrating the lock itself, not just its happy path.

import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';

const REST_SERVICE_URL = __ENV.REST_SERVICE_URL || 'http://localhost:8081';

// How many VUs simultaneously race the same brand-new Idempotency-Key in concurrent_lock_demo.
// shared-iterations with vus === iterations is k6's standard pattern for firing N requests at
// effectively the same instant (every VU is started together and immediately claims one of the
// N shared iterations) — see https://k6.io/docs/using-k6/scenarios/concurrency-and-race-conditions/.
const CONCURRENT_VUS = Number(__ENV.CONCURRENT_VUS || 5);

// Gives idempotency_demo (started at t=0) time to finish its handful of sequential requests
// before concurrent_lock_demo starts. idempotency_demo has no sleeps and does ~4 HTTP calls, so
// this is a generous safety margin, not a measured minimum.
const CONCURRENT_START_OFFSET_SECONDS = Number(__ENV.CONCURRENT_START_OFFSET_SECONDS || 5);

// Aggregated across every VU in concurrent_lock_demo, so the "exactly one winner" assertion
// doesn't require the VUs to share state directly — k6 aggregates Counters test-wide regardless
// of which VU incremented them.
const acquiredCount = new Counter('idempotency_concurrent_acquired');
const rejectedCount = new Counter('idempotency_concurrent_rejected');

export const options = {
  scenarios: {
    idempotency_demo: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      exec: 'sequentialDemo',
      maxDuration: '30s',
    },
    concurrent_lock_demo: {
      executor: 'shared-iterations',
      vus: CONCURRENT_VUS,
      iterations: CONCURRENT_VUS,
      exec: 'concurrentDemo',
      startTime: `${CONCURRENT_START_OFFSET_SECONDS}s`,
      maxDuration: '30s',
    },
  },
  thresholds: {
    checks: ['rate==1.0'],
    idempotency_concurrent_acquired: ['count==1'],
    idempotency_concurrent_rejected: [`count==${CONCURRENT_VUS - 1}`],
  },
};

function freshKey(label) {
  return `k6-idem-${label}-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function postOrder(key, body) {
  const params = { headers: { 'Content-Type': 'application/json' } };
  if (key !== null) {
    params.headers['Idempotency-Key'] = key;
  }
  return http.post(`${REST_SERVICE_URL}/orders`, JSON.stringify(body), params);
}

// setup() runs exactly once, before either scenario, so this is the one place a value can be
// generated that both scenarios (and every VU within concurrent_lock_demo) agree on.
export function setup() {
  return {
    concurrentKey: freshKey('concurrent'),
    concurrentBody: { customerEmail: 'k6-idempotency-concurrent@example.com', amount: 9.99 },
  };
}

export function sequentialDemo() {
  const key = freshKey('sequential');
  const body = { customerEmail: 'k6-idempotency-sequential@example.com', amount: 19.99 };

  console.log(`[sequential] Stage 1: POST /orders with a fresh Idempotency-Key (${key})`);
  const first = postOrder(key, body);
  const created = check(first, {
    'fresh key creates the order (201)': (r) => r.status === 201,
  });
  if (!created) {
    fail(`fresh key was not accepted: status ${first.status}, body ${first.body}`);
  }
  const orderId = first.json('id');
  console.log(`[sequential] Stage 1 OK: order ${orderId} created.`);

  console.log('[sequential] Stage 2: replay the same key + same body');
  const replay = postOrder(key, body);
  const replayed = check(replay, {
    'replay returns 201': (r) => r.status === 201,
    'replay returns the SAME order id, not a new one': (r) => r.json('id') === orderId,
  });
  if (!replayed) {
    fail(
      `replay did not return the original order: status ${replay.status}, body ${replay.body} ` +
        `(expected id ${orderId})`,
    );
  }
  console.log(`[sequential] Stage 2 OK: replay returned the original order ${orderId}, no duplicate created.`);

  console.log('[sequential] Stage 3: reuse the key with a DIFFERENT body');
  const conflict = postOrder(key, { customerEmail: 'someone-else@example.com', amount: 1.0 });
  const conflicted = check(conflict, {
    'reusing the key with a different body returns 409 Conflict': (r) => r.status === 409,
  });
  if (!conflicted) {
    fail(`conflicting replay was not rejected: status ${conflict.status}, body ${conflict.body}`);
  }
  console.log('[sequential] Stage 3 OK: conflicting body was rejected with 409.');

  console.log('[sequential] Stage 4: POST /orders with no Idempotency-Key header at all');
  const missing = postOrder(null, body);
  const rejected = check(missing, {
    'missing Idempotency-Key header returns 400 Bad Request': (r) => r.status === 400,
  });
  if (!rejected) {
    fail(`missing header was not rejected: status ${missing.status}, body ${missing.body}`);
  }
  console.log('[sequential] Stage 4 OK: missing header was rejected with 400.');
}

export function concurrentDemo(data) {
  const res = postOrder(data.concurrentKey, data.concurrentBody);

  if (res.status === 201) {
    acquiredCount.add(1);
  } else if (res.status === 425) {
    rejectedCount.add(1);
  }

  const outcome = check(res, {
    'concurrent request either wins the lock (201) or is rejected as in-progress (425)': (r) =>
      r.status === 201 || r.status === 425,
  });
  if (!outcome) {
    fail(`unexpected status racing for a shared key: ${res.status}, body ${res.body}`);
  }

  console.log(`[concurrent] VU ${__VU} got ${res.status} for the shared key.`);
}
