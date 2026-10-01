package uk.bit1.outbox.rest.idempotency;

import java.util.UUID;

// Outcome of IdempotencyStore.tryBegin: tells IdempotencyAspect what to do with the guarded
// method — proceed, reject, or replay a prior result. See ADR 0006.
sealed interface LockResult {
    record Acquired() implements LockResult {}
    record InProgress() implements LockResult {}
    record Replayable(UUID resultId) implements LockResult {}
    record Conflict() implements LockResult {}
}
