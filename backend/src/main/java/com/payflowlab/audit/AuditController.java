package com.payflowlab.audit;

import com.payflowlab.common.PageResponse;
import com.payflowlab.common.Paging;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-events")
@Tag(name = "Audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    public record AuditEventResponse(UUID id, Instant occurredAt, String entityType, String entityId, String action,
                                     String actor, String metadata) {
    }

    @GetMapping
    @Operation(summary = "Search the audit trail")
    public PageResponse<AuditEventResponse> search(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return PageResponse.of(
                auditService.search(entityType, entityId, action,
                        Paging.of(page, size, null, Set.of("occurredAt"), "occurredAt")),
                e -> new AuditEventResponse(e.getId(), e.getOccurredAt(), e.getEntityType(), e.getEntityId(),
                        e.getAction(), e.getActor(), e.getMetadata()));
    }
}
