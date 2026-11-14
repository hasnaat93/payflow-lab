package com.payflowlab.merchant;

import com.payflowlab.audit.AuditService;
import com.payflowlab.common.ApiException;
import com.payflowlab.common.AppClock;
import com.payflowlab.common.Currencies;
import com.payflowlab.fee.FeePlan;
import com.payflowlab.fee.FeePlanService;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantService {

    private final MerchantRepository repository;
    private final FeePlanService feePlans;
    private final AuditService audit;
    private final AppClock clock;

    public MerchantService(MerchantRepository repository, FeePlanService feePlans, AuditService audit,
                           AppClock clock) {
        this.repository = repository;
        this.feePlans = feePlans;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<Merchant> search(String query, Merchant.Status status, Pageable pageable) {
        String pattern = (query == null || query.isBlank())
                ? null
                : "%" + query.trim().toLowerCase(Locale.ROOT).replace("%", "\\%").replace("_", "\\_") + "%";
        return repository.search(pattern, status, pageable);
    }

    @Transactional(readOnly = true)
    public Merchant get(UUID id) {
        return repository.findWithFeePlan(id).orElseThrow(() -> ApiException.notFound("Merchant", id));
    }

    @Transactional
    public Merchant create(String merchantCode, String displayName, String legalName, String currency,
                           UUID feePlanId) {
        Currencies.require(currency);
        if (repository.existsByMerchantCode(merchantCode)) {
            throw ApiException.conflict("MERCHANT_CODE_TAKEN",
                    "A merchant with code " + merchantCode + " already exists");
        }
        FeePlan plan = usablePlan(feePlanId, currency);
        Merchant merchant = repository.save(
                new Merchant(merchantCode, displayName, legalName, currency, plan, clock.now()));
        audit.record("MERCHANT", merchant.getId(), "MERCHANT_CREATED", AuditService.OPERATOR,
                Map.of("merchantCode", merchantCode, "currency", currency, "feePlan", plan.getCode()));
        return merchant;
    }

    @Transactional
    public Merchant update(UUID id, String displayName, String legalName, UUID feePlanId) {
        Merchant merchant = get(id);
        FeePlan plan = merchant.getFeePlan().getId().equals(feePlanId)
                ? merchant.getFeePlan()
                : usablePlan(feePlanId, merchant.getCurrency());
        merchant.update(displayName, legalName, plan, clock.now());
        audit.record("MERCHANT", merchant.getId(), "MERCHANT_UPDATED", AuditService.OPERATOR,
                Map.of("merchantCode", merchant.getMerchantCode(), "feePlan", plan.getCode()));
        return merchant;
    }

    @Transactional
    public Merchant changeStatus(UUID id, Merchant.Status target) {
        Merchant merchant = get(id);
        if (merchant.getStatus() == target) {
            throw ApiException.conflict("MERCHANT_STATUS_UNCHANGED", "Merchant is already " + target);
        }
        merchant.setStatus(target, clock.now());
        audit.record("MERCHANT", merchant.getId(),
                target == Merchant.Status.ACTIVE ? "MERCHANT_ACTIVATED" : "MERCHANT_SUSPENDED",
                AuditService.OPERATOR, Map.of("merchantCode", merchant.getMerchantCode()));
        return merchant;
    }

    private FeePlan usablePlan(UUID feePlanId, String merchantCurrency) {
        FeePlan plan = feePlans.get(feePlanId);
        if (!plan.isActive()) {
            throw ApiException.unprocessable("FEE_PLAN_INACTIVE", "Fee plan " + plan.getCode() + " is inactive");
        }
        // A fixed fee of 30 means 0.30 in the plan's currency; applying it to another currency would be wrong.
        if (!plan.getCurrency().equals(merchantCurrency)) {
            throw ApiException.unprocessable("FEE_PLAN_CURRENCY_MISMATCH", "Fee plan " + plan.getCode()
                    + " is in " + plan.getCurrency() + " but the merchant settles in " + merchantCurrency);
        }
        return plan;
    }
}
