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
//   2. concurrent_lock_demo (CONCURRENT_REQUESTS requests fired at once via http.batch, all
//      racing one pre-shared key): every response is either 201 (the lock was acquired, or the
//      key had already completed and this is a replay) or 425 (another request currently holds
//      the lock) — and, critically, every 201 among them carries the SAME order id. That's the
//      actual safety property: no duplicate Order was created, regardless of how the race
//      happened to interleave. See "Why assert on order ids, not on the 201/425 split" below.

import http from 'k6/http';
import { check, fail } from 'k6';

const REST_SERVICE_URL = __ENV.REST_SERVICE_URL || 'http://localhost:8081';

// How many requests race the same brand-new Idempotency-Key in concurrent_lock_demo, fired
// together via http.batch from a single VU (see the comment on concurrentDemo for why a single
// VU rather than many).
const CONCURRENT_REQUESTS = Number(__ENV.CONCURRENT_REQUESTS || 5);

// Gives idempotency_demo (started at t=0) time to finish its handful of sequential requests
// before concurrent_lock_demo starts. idempotency_demo has no sleeps and does ~4 HTTP calls, so
// this is a generous safety margin, not a measured minimum.
const CONCURRENT_START_OFFSET_SECONDS = Number(__ENV.CONCURRENT_START_OFFSET_SECONDS || 5);

export const options = {
  // http.batch queues requests past these limits instead of firing them all at once, and since
  // every concurrent_lock_demo request targets the same host, the default batchPerHost of 6
  // would silently serialize part of the race for any CONCURRENT_REQUESTS above that — turning
  // "requests racing simultaneously" into "requests racing, then replaying" without erroring.
  // Both limits are raised to match the configured concurrency so the setting actually controls
  // how many requests go out at once.
  batch: CONCURRENT_REQUESTS,
  batchPerHost: CONCURRENT_REQUESTS,
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
      vus: 1,
      iterations: 1,
      exec: 'concurrentDemo',
      startTime: `${CONCURRENT_START_OFFSET_SECONDS}s`,
      maxDuration: '30s',
    },
  },
  thresholds: {
    checks: ['rate==1.0'],
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
// generated that both scenarios agree on.
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

// Why assert on order ids, not on the 201/425 split:
//
// OrderController's @ResponseStatus(201) is fixed on the method, so BOTH a genuine lock
// acquisition (LockResult.Acquired, which actually runs createOrder) and a replay
// (LockResult.Replayable, which just re-fetches the already-completed order) come back as 201 —
// only LockResult.InProgress becomes 425. That means "how many responses were 201" is a function
// of exactly how the race interleaved with each request's processing time, not of correctness:
// if the winner finishes before a later racer's request is even handled, that racer legitimately
// replays and also gets 201. A fixed "exactly one 201" threshold is therefore flaky by
// construction — it was observed failing locklessly (4x 201, 1x 425) while the actual safety
// property (one Order, no duplicates) still held.
//
// The property that actually matters is: every 201 returned during the race agrees on the same
// order id. If the lock were broken and two requests both ran createOrder for real, they'd
// create two different Order rows and return two different ids — THAT'S what this checks for,
// regardless of how many requests happened to see "in progress" vs "already completed".
//
// Firing the race via a single VU's http.batch (rather than N separate k6 VUs) is what makes the
// comparison possible at all: k6 VUs run in isolated JS contexts and can't share state with each
// other, but http.batch's responses all land back in the same VU/iteration, as a plain array, so
// they can be compared directly.
export function concurrentDemo(data) {
  const requests = [];
  for (let i = 0; i < CONCURRENT_REQUESTS; i++) {
    requests.push([
      'POST',
      `${REST_SERVICE_URL}/orders`,
      JSON.stringify(data.concurrentBody),
      { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': data.concurrentKey } },
    ]);
  }

  console.log(
    `[concurrent] Firing ${CONCURRENT_REQUESTS} concurrent POST /orders requests sharing one Idempotency-Key (${data.concurrentKey})`,
  );
  const responses = http.batch(requests);

  const createdIds = [];
  let acquiredOrReplayedCount = 0;
  let inProgressCount = 0;

  responses.forEach((res, i) => {
    const validStatus = check(res, {
      'concurrent request either creates/replays the order (201) or is rejected as in-progress (425)': (r) =>
        r.status === 201 || r.status === 425,
    });
    if (!validStatus) {
      fail(`request ${i} got an unexpected status racing for a shared key: ${res.status}, body ${res.body}`);
    }

    if (res.status === 201) {
      acquiredOrReplayedCount++;
      createdIds.push(res.json('id'));
      console.log(`[concurrent] request ${i} got 201 (order ${res.json('id')}).`);
    } else {
      inProgressCount++;
      console.log(`[concurrent] request ${i} got 425 (lock already held).`);
    }
  });

  console.log(
    `[concurrent] ${acquiredOrReplayedCount} request(s) got 201 (acquired the lock or replayed an ` +
      `already-completed one), ${inProgressCount} got 425 (lock in progress). A 201 can mean either ` +
      "outcome, so that split alone doesn't prove correctness — the order-id check below does.",
  );

  const atLeastOneCreated = check(createdIds, {
    'at least one concurrent request got 201': (ids) => ids.length > 0,
  });
  if (!atLeastOneCreated) {
    fail('every concurrent request was rejected with 425 — the lock was never acquired at all');
  }

  const distinctIds = new Set(createdIds);
  const noDuplicateOrder = check(distinctIds, {
    'every 201 response during the race returned the SAME order id (no duplicate order was created)': (ids) =>
      ids.size === 1,
  });
  if (!noDuplicateOrder) {
    fail(
      `expected every concurrent 201 to agree on one order id, got ${distinctIds.size} distinct ids: ` +
        `${[...distinctIds].join(', ')}`,
    );
  }

  console.log(`[concurrent] OK: every 201 response agreed on order ${[...distinctIds][0]}, no duplicate created.`);
}
