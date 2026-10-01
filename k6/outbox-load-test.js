// End-to-end load + verify test for the transactional outbox pattern demo.
//
// Run this manually via the k6 CLI against an already-running Kind cluster (see ./up.sh and
// README.md) — it is not part of the cluster's own manifests.
//
// What it proves, using only rest-service's and email-service's public HTTP interfaces
// (no direct assertions against Kafka, Postgres, or Kafka Connect):
//   1. A burst of orders posted faster than the downstream rate limit all succeed (201).
//   2. Every order's confirmation email is eventually sent (emails.sent reaches the order count).
//   3. Reaching that count takes at least as long as the rate limiter mandates — so the test
//      fails if the rate limit is silently removed/bypassed and delivery "succeeds" too fast.

import http from 'k6/http';
import { check, sleep, fail } from 'k6';

const REST_SERVICE_URL = __ENV.REST_SERVICE_URL || 'http://localhost:8081';
const EMAIL_SERVICE_URL = __ENV.EMAIL_SERVICE_URL || 'http://localhost:8082';

// email-service runs 2 replicas behind a single-partition topic, so only one replica's JVM
// ever holds a non-zero emails.sent counter (see k8s/05-email-service.yaml). Polling
// EMAIL_SERVICE_URL directly (a load-balanced Service) only sees whichever pod answers, which
// flakes when that's the idle replica. Set PROMETHEUS_URL (the `prometheus-nodeport` Service,
// :30390) to instead sum the counter across every replica via PromQL, which is correct
// regardless of which pod is consuming — see README.md's "Run the k6 end-to-end test" section.
const PROMETHEUS_URL = __ENV.PROMETHEUS_URL || '';

const ORDER_COUNT = Number(__ENV.ORDER_COUNT || 20);
const LOAD_DURATION_SECONDS = Number(__ENV.LOAD_DURATION_SECONDS || 10);

// Must match the resilience4j RateLimiter config in
// email-service/src/main/java/com/example/outbox/email/order/ConfirmationService.java (see ADR
// 0002) — that file is the source of truth; these are not read from it automatically.
const RATE_LIMIT_FOR_PERIOD = Number(__ENV.RATE_LIMIT_FOR_PERIOD || 5);
const RATE_LIMIT_PERIOD_SECONDS = Number(__ENV.RATE_LIMIT_PERIOD_SECONDS || 10);

const POLL_INTERVAL_SECONDS = Number(__ENV.POLL_INTERVAL_SECONDS || 1);
const POLL_TIMEOUT_SECONDS = Number(__ENV.POLL_TIMEOUT_SECONDS || 90);

// The theoretical minimum time to drain ORDER_COUNT events through a rate limiter that
// releases RATE_LIMIT_FOR_PERIOD permits every RATE_LIMIT_PERIOD_SECONDS is
// (ceil(ORDER_COUNT / RATE_LIMIT_FOR_PERIOD) - 1) * RATE_LIMIT_PERIOD_SECONDS, achieved only
// if every order already exists the instant a permit frees up. Orders actually trickle in
// over LOAD_DURATION_SECONDS, which only ever pushes real completion later. Applying
// MIN_DURATION_SAFETY_FACTOR below that theoretical floor keeps the assertion from flaking on
// timing jitter or slower CDC/Kafka propagation (e.g. on a loaded CI runner) while staying far
// above what an unthrottled run completes in (which is roughly LOAD_DURATION_SECONDS plus
// propagation lag — empirically ~11s for the defaults, against a throttled ~28-30s).
const MIN_DURATION_SAFETY_FACTOR = Number(__ENV.MIN_DURATION_SAFETY_FACTOR || 0.6);

export const options = {
  scenarios: {
    outbox_end_to_end: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: `${POLL_TIMEOUT_SECONDS + LOAD_DURATION_SECONDS + 30}s`,
    },
  },
  thresholds: {
    checks: ['rate==1.0'],
  },
};

// Sums emails.sent (exposed to Prometheus as emails_sent_total by Micrometer's naming
// convention) across every email-service replica, so it doesn't matter which pod(s) are
// actually consuming.
function emailsSentCountFromPrometheus() {
  const url = `${PROMETHEUS_URL}/api/v1/query?query=${encodeURIComponent('sum(emails_sent_total)')}`;
  const res = http.get(url);
  if (res.status !== 200) {
    fail(`unexpected status ${res.status} polling ${url}: ${res.body}`);
  }
  const body = res.json();
  if (body.status !== 'success') {
    fail(`Prometheus query failed: ${res.body}`);
  }
  const result = body.data.result;
  if (result.length === 0) {
    // Defensive: treat a not-yet-registered counter as zero rather than failing the poll.
    return 0;
  }
  return Number(result[0].value[1]);
}

function emailsSentCountFromActuator() {
  const res = http.get(`${EMAIL_SERVICE_URL}/actuator/metrics/emails.sent`);
  if (res.status === 404) {
    // Defensive: treat a not-yet-registered counter as zero rather than failing the poll.
    return 0;
  }
  if (res.status !== 200) {
    fail(`unexpected status ${res.status} polling ${EMAIL_SERVICE_URL}/actuator/metrics/emails.sent: ${res.body}`);
  }
  const body = res.json();
  const measurement = body.measurements.find((m) => m.statistic === 'COUNT');
  return measurement.value;
}

function emailsSentCount() {
  return PROMETHEUS_URL ? emailsSentCountFromPrometheus() : emailsSentCountFromActuator();
}

function postOrder(index) {
  const payload = JSON.stringify({
    customerEmail: `k6-loadtest-${__VU}-${index}-${Date.now()}@example.com`,
    amount: 9.99,
  });
  return http.post(`${REST_SERVICE_URL}/orders`, payload, {
    headers: { 'Content-Type': 'application/json' },
  });
}

export default function () {
  console.log(
    `Load stage: posting ${ORDER_COUNT} orders over ~${LOAD_DURATION_SECONDS}s ` +
      `(rate limiter: ${RATE_LIMIT_FOR_PERIOD} per ${RATE_LIMIT_PERIOD_SECONDS}s)`,
  );

  const baseline = emailsSentCount();
  const loadStartMs = Date.now();
  const sleepBetweenPostsSeconds = LOAD_DURATION_SECONDS / ORDER_COUNT;

  for (let i = 0; i < ORDER_COUNT; i++) {
    const res = postOrder(i);
    const created = check(res, {
      'POST /orders returns 201': (r) => r.status === 201,
    });
    if (!created) {
      fail(`order ${i} was not created: status ${res.status}, body ${res.body}`);
    }
    if (i < ORDER_COUNT - 1) {
      sleep(sleepBetweenPostsSeconds);
    }
  }

  console.log(`Load stage complete: ${ORDER_COUNT} orders created, all 201.`);

  const target = baseline + ORDER_COUNT;
  const source = PROMETHEUS_URL
    ? `${PROMETHEUS_URL} (sum(emails_sent_total) across replicas)`
    : `${EMAIL_SERVICE_URL}/actuator/metrics/emails.sent`;
  console.log(`Verify stage: polling ${source} for emails.sent to reach ${target} (baseline was ${baseline})`);

  let current = emailsSentCount();
  const pollDeadlineMs = Date.now() + POLL_TIMEOUT_SECONDS * 1000;
  while (current < target && Date.now() < pollDeadlineMs) {
    sleep(POLL_INTERVAL_SECONDS);
    current = emailsSentCount();
  }
  const verifyEndMs = Date.now();

  const reachedTarget = check(current, {
    [`emails.sent reaches ${target} within ${POLL_TIMEOUT_SECONDS}s`]: (c) => c >= target,
  });
  if (!reachedTarget) {
    fail(
      `emails.sent only reached ${current}/${target} after ${POLL_TIMEOUT_SECONDS}s ` +
        '(an event may have been dropped, or the pipeline is slower than the poll timeout allows)',
    );
  }

  const totalDurationSeconds = (verifyEndMs - loadStartMs) / 1000;
  const batches = Math.ceil(ORDER_COUNT / RATE_LIMIT_FOR_PERIOD);
  const theoreticalMinSeconds = (batches - 1) * RATE_LIMIT_PERIOD_SECONDS;
  const minExpectedSeconds = theoreticalMinSeconds * MIN_DURATION_SAFETY_FACTOR;

  console.log(
    `Verify stage complete: emails.sent reached ${target} after ${totalDurationSeconds.toFixed(1)}s ` +
      `(minimum expected under throttling: ${minExpectedSeconds.toFixed(1)}s)`,
  );

  check(totalDurationSeconds, {
    'delivery took at least as long as the rate limiter mandates (throttling was actually exercised)': (d) =>
      d >= minExpectedSeconds,
  });
}
