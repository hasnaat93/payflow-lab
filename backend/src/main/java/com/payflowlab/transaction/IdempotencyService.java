package com.payflowlab.transaction;

import com.payflowlab.common.ApiException;
import com.payflowlab.common.AppClock;
import com.payflowlab.transaction.TransactionRepositories.IdempotencyRecordRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotency keys make money-moving requests safe to retry.
 *
 * <ul>
 *   <li>First request with a key: the operation runs and the key is stored
 *       <em>in the same transaction</em> as its effect.</li>
 *   <li>Same key, same payload: the original result is returned; nothing runs twice.</li>
 *   <li>Same key, different payload: rejected with 422, because the client reused a key by mistake.</li>
 *   <li>Two identical requests racing: the unique constraint lets exactly one commit;
 *       the other fails with 409 and its whole transaction rolls back.</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;
    private final AppClock clock;

    public IdempotencyService(IdempotencyRecordRepository repository, AppClock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** @return the id of the resource created by the original request, if this is a replay. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> findReplay(String key, String operation, String canonicalRequest) {
        if (key == null) {
            return Optional.empty();
        }
        return repository.findByIdempotencyKeyAndOperation(key, operation).map(existing -> {
            if (!existing.getRequestHash().equals(sha256(canonicalRequest))) {
                throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used with a different request payload");
            }
            return existing.getResourceId();
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void remember(String key, String operation, String canonicalRequest, UUID resourceId) {
        if (key == null) {
            return;
        }
        // flush now so a racing duplicate fails here, inside the operation's transaction
        repository.saveAndFlush(
                new IdempotencyRecord(key, operation, sha256(canonicalRequest), resourceId, clock.now()));
    }

    public static String validateKey(String key) {
        if (key == null) {
            return null;
        }
        String trimmed = key.trim();
        if (trimmed.isEmpty() || trimmed.length() > 80 || !trimmed.matches("^[A-Za-z0-9._:-]+$")) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 1-80 characters: letters, digits, '.', '_', ':' or '-'");
        }
        return trimmed;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
