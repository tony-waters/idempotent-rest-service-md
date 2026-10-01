# Transactional Outbox Pattern (prototype)

A demo system showing the transactional outbox pattern: an order-placing service writes state and an outbound event in one transaction, and a downstream service reacts to that event via change-data-capture.

## Language

**Order**:
The aggregate created by `rest-service` when a customer places an order. Carries `id`, `customerEmail`, `amount`, `createdAt`. Has no lifecycle/status in this prototype — an Order is created once and never transitions or updates.
_Avoid_: Purchase, transaction

**OrderCreated**:
The single outbox event type emitted at Order creation, in the same database transaction as the Order write. This is the only event the outbox ever emits — there are no other event types.
_Avoid_: OrderPlaced, OrderReceived (the idea doc's prose used "order received" informally; the canonical event name is `OrderCreated`)

**Outbox**:
The table `rest-service` writes to in the same transaction as the Order write, holding events not yet relayed to Kafka. Debezium's CDC connector reads this table's changes; it is never read by any other application code. Carries a `trace_context` column holding the W3C `traceparent` of the request that created the row, with no business meaning of its own — it exists solely so Debezium can place it as a Kafka header and `email-service` can continue the same trace across the CDC hop (see [ADR 0005](docs/adr/0005-trace-context-through-outbox.md)).
_Avoid_: Event log, message queue (it is neither — it's a plain table, relayed by CDC)
