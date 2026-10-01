package uk.bit1.outbox.rest.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Marks the String parameter of an @Idempotent method that carries the client-supplied key.
// All other parameters are fingerprinted to detect a replayed key paired with a changed body.
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface IdempotencyKey {
}
