package uk.bit1.outbox.rest.idempotency;

import io.micrometer.core.instrument.MeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.support.Repositories;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

// Reusable idempotency mechanism per ADR 0006: guards any @Idempotent method against concurrent
// or replayed invocation under the same client-supplied key. Runs outside (around) @Transactional
// so the lock covers the whole business operation and a rollback is visible before the lock is
// released — see the explicit @Order below. The Redis lock protocol itself lives in
// IdempotencyStore; this class only does AOP/reflection plumbing, maps outcomes to HTTP, and
// records each terminal outcome (created/replayed/conflict/in_progress) as idempotency.outcomes
// for the Grafana dashboard in k8s/monitoring/04-idempotency-dashboard.yaml. Each outcome is only
// recorded once its branch has actually succeeded — "created" after the business method and
// store.complete() both return (a thrown exception just frees the lock for a clean retry, see
// proceedAndRecord), "replayed" after replay() has actually re-fetched the original result — so a
// downstream failure in either path isn't miscounted as a settled outcome.
@Aspect
@Component
@Order(0)
class IdempotencyAspect {

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;
    private final Repositories repositories;
    private final MeterRegistry meterRegistry;

    IdempotencyAspect(IdempotencyStore store, ObjectMapper objectMapper, ApplicationContext applicationContext, MeterRegistry meterRegistry) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.repositories = new Repositories(applicationContext);
        this.meterRegistry = meterRegistry;
    }

    @Around("@annotation(Idempotent)")
    Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Object[] args = joinPoint.getArgs();
        String scope = method.getDeclaringClass().getName() + "." + method.getName();
        String idempotencyKey = extractIdempotencyKey(method, args);
        String fingerprint = fingerprint(method, args);

        LockResult result = store.tryBegin(scope, idempotencyKey, fingerprint);
        return switch (result) {
            case LockResult.Acquired() -> proceedAndRecord(joinPoint, scope, idempotencyKey, fingerprint);
            case LockResult.InProgress() -> {
                recordOutcome("in_progress", scope);
                throw new ResponseStatusException(HttpStatus.TOO_EARLY, "A request with this Idempotency-Key is already in progress");
            }
            case LockResult.Conflict() -> {
                recordOutcome("conflict", scope);
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key was already used with a different request body");
            }
            case LockResult.Replayable(UUID resultId) -> {
                Object replayed = replay(method.getReturnType(), resultId);
                recordOutcome("replayed", scope);
                yield replayed;
            }
        };
    }

    private Object proceedAndRecord(ProceedingJoinPoint joinPoint, String scope, String key, String fingerprint) throws Throwable {
        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable t) {
            store.release(scope, key);
            throw t;
        }
        store.complete(scope, key, fingerprint, extractId(result));
        recordOutcome("created", scope);
        return result;
    }

    private void recordOutcome(String outcome, String scope) {
        meterRegistry.counter("idempotency.outcomes", "outcome", outcome, "scope", scope).increment();
    }

    @SuppressWarnings("unchecked")
    private Object replay(Class<?> returnType, UUID id) {
        CrudRepository<Object, Object> repository = (CrudRepository<Object, Object>) repositories.getRepositoryFor(returnType)
                .orElseThrow(() -> new IllegalStateException("No repository registered for idempotent replay of " + returnType));
        return repository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Idempotent replay target " + id + " no longer exists"));
    }

    private UUID extractId(Object entity) {
        try {
            Method getId = entity.getClass().getMethod("getId");
            return (UUID) getId.invoke(entity);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("@Idempotent method must return a type exposing a public getId(): UUID", e);
        }
    }

    private String extractIdempotencyKey(Method method, Object[] args) {
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].isAnnotationPresent(IdempotencyKey.class)) {
                return (String) args[i];
            }
        }
        throw new IllegalStateException("@Idempotent method " + method + " has no @IdempotencyKey parameter");
    }

    private String fingerprint(Method method, Object[] args) {
        Parameter[] parameters = method.getParameters();
        List<Object> fingerprinted = new ArrayList<>();
        for (int i = 0; i < parameters.length; i++) {
            if (!parameters[i].isAnnotationPresent(IdempotencyKey.class)) {
                fingerprinted.add(args[i]);
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(fingerprinted));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
