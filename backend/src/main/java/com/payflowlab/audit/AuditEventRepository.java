package com.payflowlab.audit;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    @Query("""
            select e from AuditEvent e
            where (:entityType is null or e.entityType = :entityType)
              and (:entityId is null or e.entityId = :entityId)
              and (:action is null or e.action = :action)
            """)
    Page<AuditEvent> search(@Param("entityType") String entityType, @Param("entityId") String entityId,
                            @Param("action") String action, Pageable pageable);
}
