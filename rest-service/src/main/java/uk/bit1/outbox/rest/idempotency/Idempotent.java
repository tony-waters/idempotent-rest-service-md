package uk.bit1.outbox.rest.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Marks a method whose result should be cached by client-supplied idempotency key, via
// IdempotencyAspect. See ADR 0006. One of the method's parameters must carry @IdempotencyKey,
// and the method must return a Spring Data-managed entity exposing a public getId(): UUID, so
// a replay can re-fetch it rather than re-running the method.
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
}
