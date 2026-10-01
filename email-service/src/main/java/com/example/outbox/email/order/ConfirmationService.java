package com.example.outbox.email.order;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class ConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationService.class);

    private final Counter emailsSent;

    ConfirmationService(MeterRegistry meterRegistry) {
        this.emailsSent = Counter.builder("emails.sent").register(meterRegistry);
    }

    // Rate/timeout config for "emailSender" lives in application.yml — see the comment there
    // for why the timeout is a very long duration rather than a literal block-forever (ADR 0002).
    @RateLimiter(name = "emailSender")
    void send(OrderCreatedEvent event) {
        log.info("Sent confirmation email to {} for order {} ({})", event.customerEmail(), event.orderId(), event.amount());
        emailsSent.increment();
    }
}
