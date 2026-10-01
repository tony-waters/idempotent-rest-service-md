package uk.bit1.outbox.rest.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.idempotency")
public class IdempotencyProperties {

    private Duration ttl = Duration.ofHours(24);

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }
}
