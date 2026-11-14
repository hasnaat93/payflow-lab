package com.payflowlab.common;

import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Builds a safe Pageable: bounded page size and a whitelist of sortable fields. */
public final class Paging {

    public static final int MAX_SIZE = 100;

    private Paging() {
    }

    /**
     * @param sort         "field,direction", e.g. {@code createdAt,desc}
     * @param allowed      properties clients may sort by (prevents sorting on arbitrary columns)
     * @param defaultField used when no sort is requested
     */
    public static Pageable of(int page, int size, String sort, Set<String> allowed, String defaultField) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw ApiException.badRequest("INVALID_PAGING", "page must be >= 0 and size between 1 and " + MAX_SIZE);
        }
        String field = defaultField;
        Sort.Direction direction = Sort.Direction.DESC;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            field = parts[0].trim();
            if (!allowed.contains(field)) {
                throw ApiException.badRequest("INVALID_SORT",
                        "Sortable fields: " + String.join(", ", allowed.stream().sorted().toList()));
            }
            if (parts.length > 1) {
                direction = "asc".equalsIgnoreCase(parts[1].trim()) ? Sort.Direction.ASC : Sort.Direction.DESC;
            }
        }
        // id is a deterministic tie-breaker so pages never overlap or skip rows
        return PageRequest.of(page, size, Sort.by(direction, field).and(Sort.by(Sort.Direction.DESC, "id")));
    }
}
