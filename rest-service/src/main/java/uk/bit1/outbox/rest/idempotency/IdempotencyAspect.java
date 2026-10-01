package uk.bit1.outbox.rest.idempotency;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
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
// released — see the explicit @Order below.
@Aspect
@Component
@Order(0)
class IdempotencyAspect {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final IdempotencyProperties properties;
    private final Repositories repositories;

    IdempotencyAspect(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                       IdempotencyProperties properties, ApplicationContext applicationContext) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.repositories = new Repositories(applicationContext);
    }

    private static final int MAX_LOCK_ATTEMPTS = 2;

    @Around("@annotation(Idempotent)")
    Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Object[] args = joinPoint.getArgs();
        String idempotencyKey = extractIdempotencyKey(method, args);
        String fingerprint = fingerprint(method, args);
        String redisKey = "idempotency:" + method.getDeclaringClass().getName() + "." + method.getName() + ":" + idempotencyKey;

        for (int attempt = 1; attempt <= MAX_LOCK_ATTEMPTS; attempt++) {
            boolean acquired = Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                    redisKey, write(new IdempotencyRecord(IdempotencyRecord.Status.IN_PROGRESS, fingerprint, null)), properties.getTtl()));
            if (acquired) {
                return proceedAndRecord(joinPoint, redisKey, fingerprint);
            }

            IdempotencyRecord existing = read(redisTemplate.opsForValue().get(redisKey));
            if (existing == null) {
                // Key vanished between the setIfAbsent above and this read (expired, or deleted by
                // a concurrent failed attempt): retry the atomic acquisition rather than proceeding
                // without a lock.
                continue;
            }
            if (existing.status() == IdempotencyRecord.Status.IN_PROGRESS) {
                throw new ResponseStatusException(HttpStatus.TOO_EARLY, "A request with this Idempotency-Key is already in progress");
            }
            if (!existing.fingerprint().equals(fingerprint)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key was already used with a different request body");
            }
            return replay(method.getReturnType(), existing.resultId());
        }
        throw new IllegalStateException("Could not acquire or read idempotency state for " + redisKey + " after " + MAX_LOCK_ATTEMPTS + " attempts");
    }

    private Object proceedAndRecord(ProceedingJoinPoint joinPoint, String redisKey, String fingerprint) throws Throwable {
        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable t) {
            redisTemplate.delete(redisKey);
            throw t;
        }
        UUID id = extractId(result);
        redisTemplate.opsForValue().set(redisKey,
                write(new IdempotencyRecord(IdempotencyRecord.Status.COMPLETED, fingerprint, id)), properties.getTtl());
        return result;
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

    private String write(IdempotencyRecord record) {
        return objectMapper.writeValueAsString(record);
    }

    private IdempotencyRecord read(String json) {
        return json == null ? null : objectMapper.readValue(json, IdempotencyRecord.class);
    }
}
