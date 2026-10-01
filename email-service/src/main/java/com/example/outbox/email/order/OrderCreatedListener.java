package com.example.outbox.email.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class OrderCreatedListener {

    static final String OUTBOX_ORDER_TOPIC = "outbox.event.order";

    private final ObjectMapper objectMapper;
    private final ConfirmationService confirmationService;

    OrderCreatedListener(ObjectMapper objectMapper, ConfirmationService confirmationService) {
        this.objectMapper = objectMapper;
        this.confirmationService = confirmationService;
    }

    @KafkaListener(topics = OUTBOX_ORDER_TOPIC)
    void onMessage(String payload) {
        confirmationService.send(readEvent(payload));
    }

    private OrderCreatedEvent readEvent(String payload) {
        try {
            return objectMapper.readValue(payload, OrderCreatedEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize OrderCreated payload: " + payload, e);
        }
    }
}
