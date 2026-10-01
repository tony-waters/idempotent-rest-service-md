-- Column names/types follow Debezium's Outbox Event Router convention (see ADR 0001):
-- aggregatetype selects the Kafka topic, aggregateid becomes the message key,
-- type and payload become the routed message's type header and value.
CREATE TABLE outbox (
    id UUID PRIMARY KEY,
    aggregatetype VARCHAR(255) NOT NULL,
    aggregateid VARCHAR(255) NOT NULL,
    type VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL
);
