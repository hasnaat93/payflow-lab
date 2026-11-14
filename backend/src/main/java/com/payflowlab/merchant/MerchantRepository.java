package com.payflowlab.merchant;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MerchantRepository extends JpaRepository<Merchant, UUID> {

    boolean existsByMerchantCode(String merchantCode);

    Optional<Merchant> findByMerchantCode(String merchantCode);

    @EntityGraph(attributePaths = "feePlan")
    @Query("select m from Merchant m where m.id = :id")
    Optional<Merchant> findWithFeePlan(@Param("id") UUID id);

    /** Serialises settlement for one merchant: a second concurrent run blocks here until the first commits. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Merchant m where m.id = :id")
    Optional<Merchant> findForSettlement(@Param("id") UUID id);

    @EntityGraph(attributePaths = "feePlan")
    @Query("""
            select m from Merchant m
            where (:status is null or m.status = :status)
              and (:pattern is null
                   or lower(m.displayName) like :pattern
                   or lower(m.merchantCode) like :pattern)
            """)
    Page<Merchant> search(@Param("pattern") String pattern, @Param("status") Merchant.Status status,
                          Pageable pageable);
}
