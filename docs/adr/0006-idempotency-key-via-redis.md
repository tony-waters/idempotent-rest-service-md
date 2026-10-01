# Make Order creation idempotent via a client-supplied key cached in Redis, not a natural/business key or client-chosen resource ID

`POST /orders` is a side-effecting create (inserts an `Order` and an `Outbox` row in one transaction); retried on a dropped response, it could create a duplicate. We chose a Stripe-style client-supplied `Idempotency-Key` header, cached in Redis as `key → orderId`, over (a) inferring duplicates from request content — no natural unique business key exists on an order today — or (b) having the client choose the `Order` id up front, which would change the request/response contract `OrderApiTest` and every caller already depend on. Redis holds only the id mapping, not a cached response body: because `Order` is immutable post-creation (see `CONTEXT.md`), a replay simply re-fetches the current row from Postgres and re-serializes it.

The mechanism is a reusable `@Idempotent` annotation plus a Spring AOP aspect rather than logic hardcoded in `OrderController`/`OrderService`, matching ADR 0002's precedent of moving a cross-cutting concern into an annotation. Redis keys are namespaced by the annotated method's fully-qualified name (`idempotency:<method>:<key>`) so two different idempotent endpoints can never collide on the same client-supplied key value, even though only one endpoint uses this today.

## Considered Options

- **Caching the full HTTP response** instead of just the `orderId` — rejected because `Order`'s immutability makes a parallel cache unnecessary, and it would need its own invalidation story.
- **Blocking a concurrent duplicate request until the first completes**, as ADR 0002 does for the rate limiter — rejected in favor of failing fast with `425 Too Early`, since here the caller can simply retry rather than the server needing to guarantee eventual delivery.
- **Global, unscoped Redis keys** — rejected because that's only safe as long as there's exactly one idempotent endpoint, which contradicts building this as a reusable mechanism.

## Consequences

- The idempotency guarantee is bounded by the key's TTL (24h). A retry arriving after the TTL has expired is indistinguishable from a brand-new request and will create a duplicate `Order` — accepted as an intentional limit of a 24h window, not a permanent guarantee.
- A request that fails while holding the lock releases its key immediately, so a client retry with the same key can succeed on the very next attempt.
