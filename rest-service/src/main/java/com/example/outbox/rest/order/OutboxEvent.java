package com.example.outbox.rest.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "outbox")
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String aggregatetype;

    @Column(nullable = false)
    private String aggregateid;

    @Column(nullable = false)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    // No business meaning — carries the W3C traceparent so Debezium's EventRouter can place
    // it as a Kafka header and email-service can continue this trace (see ADR 0005).
    @Column(name = "trace_context")
    private String traceContext;

    protected OutboxEvent() {
    }

    public OutboxEvent(UUID id, String aggregatetype, String aggregateid, String type, String payload, String traceContext) {
        this.id = id;
        this.aggregatetype = aggregatetype;
        this.aggregateid = aggregateid;
        this.type = type;
        this.payload = payload;
        this.traceContext = traceContext;
    }
}
