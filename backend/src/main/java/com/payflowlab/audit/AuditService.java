package com.payflowlab.audit;

import com.payflowlab.common.AppClock;
import java.util.Map;
import java.util.StringJoiner;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records who did what to which entity. Audit rows are written inside the
 * caller's transaction, so an audit event exists if and only if the change it
 * describes was committed.
 */
@Service
public class AuditService {

    /** There is no login in the simulator; every action is attributed to the demo operator or the system. */
    public static final String OPERATOR = "demo-operator";
    public static final String SYSTEM = "system";

    private final AuditEventRepository repository;
    private final AppClock clock;

    public AuditService(AuditEventRepository repository, AppClock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * @param metadata small, safe key/values. Callers must never pass card data or secrets;
     *                 only identifiers, amounts and statuses belong here.
     */
    @Transactional
    public void record(String entityType, Object entityId, String action, String actor, Map<String, ?> metadata) {
        repository.save(new AuditEvent(clock.now(), entityType, String.valueOf(entityId), action, actor,
                toJson(metadata)));
    }

    @Transactional(readOnly = true)
    public Page<AuditEvent> search(String entityType, String entityId, String action, Pageable pageable) {
        return repository.search(blankToNull(entityType), blankToNull(entityId), blankToNull(action), pageable);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Minimal JSON object writer for flat metadata maps (strings, numbers, booleans). */
    static String toJson(Map<String, ?> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        StringJoiner json = new StringJoiner(",", "{", "}");
        metadata.forEach((key, value) -> {
            if (value == null) {
                return;
            }
            String rendered = (value instanceof Number || value instanceof Boolean)
                    ? value.toString()
                    : "\"" + escape(value.toString()) + "\"";
            json.add("\"" + escape(key) + "\":" + rendered);
        });
        return json.toString();
    }

    private static String escape(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 8);
        for (char c : raw.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
