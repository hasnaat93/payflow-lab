package com.payflowlab.fee;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public final class FeeRepositories {

    private FeeRepositories() {
    }

    public interface FeePlanRepository extends JpaRepository<FeePlan, UUID> {

        boolean existsByCode(String code);

        Optional<FeePlan> findByCode(String code);
    }

    public interface FeeAssessmentRepository extends JpaRepository<FeeAssessment, UUID> {

        Optional<FeeAssessment> findByTransactionId(UUID transactionId);
    }
}
