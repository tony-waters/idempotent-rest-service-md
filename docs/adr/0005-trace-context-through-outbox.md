# Propagate trace context across the CDC hop via an outbox column, not log correlation

> **Status**: accepted, implemented (see [#17](https://github.com/tony-waters/transactional-outbox-pattern-mp/issues/17)).

Debezium doesn't originate from application code holding a span context — it reads Postgres WAL and emits to Kafka on its own, so a trace started in `rest-service` doesn't naturally continue into `email-service`. We chose true end-to-end propagation: `rest-service` writes the current trace's W3C `traceparent` into the `outbox.trace_context` column in the same transaction as the `Order`/`Outbox` write, and Debezium's EventRouter SMT places that column as a Kafka message header (`table.fields.additional.placement`, no custom SMT) so `email-service` extracts it and continues the same trace. The alternative — stitching two independent traces via a shared correlation ID in logs, or not linking them at all — was rejected because the CDC hop is the one part of this system tracing is meant to make visible; tracing everything except it would miss the point of adding tracing here at all.

**Consequences**: the `outbox` table carries a field with no business meaning (see `CONTEXT.md`'s `Outbox` entry) purely to serve observability, and Debezium's EventRouter config takes on an extra `table.fields.additional.placement` mapping that a future outbox-schema change must preserve.
