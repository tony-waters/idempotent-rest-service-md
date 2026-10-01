package uk.bit1.outbox.email.order;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

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
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize OrderCreated payload: " + payload, e);
        }
    }
}
