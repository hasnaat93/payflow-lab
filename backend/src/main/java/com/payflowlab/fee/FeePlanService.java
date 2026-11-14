package com.payflowlab.fee;

import com.payflowlab.audit.AuditService;
import com.payflowlab.common.ApiException;
import com.payflowlab.common.AppClock;
import com.payflowlab.common.Currencies;
import com.payflowlab.fee.FeeRepositories.FeePlanRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeePlanService {

    private final FeePlanRepository repository;
    private final AuditService audit;
    private final AppClock clock;

    public FeePlanService(FeePlanRepository repository, AuditService audit, AppClock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<FeePlan> list() {
        return repository.findAll(Sort.by("code"));
    }

    @Transactional(readOnly = true)
    public FeePlan get(UUID id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Fee plan", id));
    }

    @Transactional
    public FeePlan create(String code, String name, String currency, int percentageBps, long fixedFeeMinor,
                          Long minFeeMinor, Long maxFeeMinor) {
        Currencies.require(currency);
        requireConsistentBounds(minFeeMinor, maxFeeMinor);
        if (repository.existsByCode(code)) {
            throw ApiException.conflict("FEE_PLAN_CODE_TAKEN", "A fee plan with code " + code + " already exists");
        }
        FeePlan plan = repository.save(new FeePlan(code, name, currency, percentageBps, fixedFeeMinor,
                minFeeMinor, maxFeeMinor, clock.now()));
        audit.record("FEE_PLAN", plan.getId(), "FEE_PLAN_CREATED", AuditService.OPERATOR,
                Map.of("code", code, "percentageBps", percentageBps, "fixedFeeMinor", fixedFeeMinor));
        return plan;
    }

    /**
     * Changes apply to future captures only: past transactions keep the terms
     * recorded in their {@link FeeAssessment} snapshot.
     */
    @Transactional
    public FeePlan update(UUID id, String name, int percentageBps, long fixedFeeMinor, Long minFeeMinor,
                          Long maxFeeMinor, Boolean active) {
        requireConsistentBounds(minFeeMinor, maxFeeMinor);
        FeePlan plan = get(id);
        plan.applyTerms(name, percentageBps, fixedFeeMinor, minFeeMinor, maxFeeMinor, clock.now());
        if (active != null) {
            plan.setActive(active, clock.now());
        }
        audit.record("FEE_PLAN", plan.getId(), "FEE_PLAN_UPDATED", AuditService.OPERATOR,
                Map.of("code", plan.getCode(), "percentageBps", percentageBps, "fixedFeeMinor", fixedFeeMinor,
                        "active", plan.isActive()));
        return plan;
    }

    private static void requireConsistentBounds(Long min, Long max) {
        if (min != null && max != null && min > max) {
            throw ApiException.badRequest("INVALID_FEE_BOUNDS", "minFeeMinor cannot be greater than maxFeeMinor");
        }
    }
}
