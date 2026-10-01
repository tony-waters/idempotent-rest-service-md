-- Carries the W3C traceparent for the request that created this event, so Debezium's
-- EventRouter SMT can place it as a Kafka header and email-service can continue the same
-- trace instead of starting a new one (see ADR 0005). No business meaning.
ALTER TABLE outbox ADD COLUMN trace_context VARCHAR(255);
