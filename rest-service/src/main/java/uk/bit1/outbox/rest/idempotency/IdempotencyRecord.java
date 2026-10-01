package uk.bit1.outbox.rest.idempotency;

import java.util.UUID;

// Redis-stored state for one idempotency key: resultId is null until the guarded method
// completes. Holds only the id, not a cached response — see ADR 0006.
record IdempotencyRecord(Status status, String fingerprint, UUID resultId) {

    enum Status {
        IN_PROGRESS, COMPLETED
    }
}
