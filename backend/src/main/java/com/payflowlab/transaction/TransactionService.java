package com.payflowlab.transaction;

import com.payflowlab.audit.AuditService;
import com.payflowlab.common.ApiException;
import com.payflowlab.common.AppClock;
import com.payflowlab.fee.FeeAssessment;
import com.payflowlab.fee.FeeCalculator;
import com.payflowlab.fee.FeePlan;
import com.payflowlab.fee.FeeRepositories.FeeAssessmentRepository;
import com.payflowlab.ledger.Account;
import com.payflowlab.ledger.JournalEntry;
import com.payflowlab.ledger.LedgerService;
import com.payflowlab.ledger.LedgerService.Line;
import com.payflowlab.ledger.LedgerService.Posting;
import com.payflowlab.merchant.Merchant;
import com.payflowlab.merchant.MerchantRepository;
import com.payflowlab.transaction.TransactionRepositories.RefundRepository;
import com.payflowlab.transaction.TransactionRepositories.TransactionEventRepository;
import com.payflowlab.transaction.TransactionRepositories.TransactionRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The payment lifecycle. Each public method is one database transaction that
 * changes the payment, writes its history event, posts to the ledger and
 * records the audit event, so all four always agree.
 */
@Service
public class TransactionService {

    public static final String SOURCE_TRANSACTION = "TRANSACTION";
    public static final String SOURCE_REFUND = "REFUND";

    public record AuthorizeCommand(UUID merchantId, long amountMinor, String currency, String cardBrand,
                                   String cardLast4, String externalReference,
                                   AuthorizationSimulator.Scenario scenario) {
    }

    /** {@code replayed} is true when an Idempotency-Key matched an earlier request and nothing was re-executed. */
    public record Result(Transaction transaction, boolean replayed) {
    }

    public record Detail(Transaction transaction, List<TransactionEvent> events, FeeAssessment feeAssessment,
                         List<Refund> refunds, List<JournalEntry> journalEntries) {
    }

    public record SearchCriteria(String query, UUID merchantId, TransactionStatus status, String currency,
                                 Instant from, Instant to) {
    }

    private final TransactionRepository transactions;
    private final TransactionEventRepository events;
    private final RefundRepository refunds;
    private final FeeAssessmentRepository feeAssessments;
    private final MerchantRepository merchants;
    private final AuthorizationSimulator simulator;
    private final IdempotencyService idempotency;
    private final LedgerService ledger;
    private final AuditService audit;
    private final AppClock clock;

    public TransactionService(TransactionRepository transactions, TransactionEventRepository events,
                              RefundRepository refunds, FeeAssessmentRepository feeAssessments,
                              MerchantRepository merchants, AuthorizationSimulator simulator,
                              IdempotencyService idempotency, LedgerService ledger, AuditService audit,
                              AppClock clock) {
        this.transactions = transactions;
        this.events = events;
        this.refunds = refunds;
        this.feeAssessments = feeAssessments;
        this.merchants = merchants;
        this.simulator = simulator;
        this.idempotency = idempotency;
        this.ledger = ledger;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- authorize

    @Transactional
    public Result authorize(AuthorizeCommand command, String idempotencyKey) {
        String canonical = String.join("|", String.valueOf(command.merchantId()),
                String.valueOf(command.amountMinor()), command.currency(), command.cardBrand(),
                command.cardLast4(), String.valueOf(command.externalReference()),
                String.valueOf(command.scenario()));
        var replay = idempotency.findReplay(idempotencyKey, "AUTHORIZE", canonical);
        if (replay.isPresent()) {
            return new Result(load(replay.get()), true);
        }

        Merchant merchant = merchants.findWithFeePlan(command.merchantId())
                .orElseThrow(() -> ApiException.notFound("Merchant", command.merchantId()));
        if (!merchant.isActive()) {
            throw ApiException.conflict("MERCHANT_SUSPENDED",
                    "Merchant " + merchant.getMerchantCode() + " is suspended and cannot accept payments");
        }
        if (!merchant.getCurrency().equals(command.currency())) {
            throw ApiException.unprocessable("CURRENCY_MISMATCH", "Merchant " + merchant.getMerchantCode()
                    + " processes " + merchant.getCurrency() + ", not " + command.currency());
        }
        String reference = command.externalReference() != null
                ? command.externalReference()
                : "PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
        if (transactions.existsByExternalReference(reference)) {
            throw ApiException.conflict("DUPLICATE_EXTERNAL_REFERENCE",
                    "A transaction with external reference " + reference + " already exists");
        }

        Instant now = clock.now();
        // an opaque demo token: generated here, derived from nothing, usable for nothing
        String token = "tok_demo_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        AuthorizationSimulator.Decision decision = simulator.decide(command.amountMinor(), command.cardLast4(),
                command.scenario() == null ? AuthorizationSimulator.Scenario.AUTO : command.scenario());

        Transaction txn = decision.approved()
                ? Transaction.authorized(reference, merchant, command.amountMinor(), command.cardBrand(),
                        command.cardLast4(), token, now)
                : Transaction.declined(reference, merchant, command.amountMinor(), command.cardBrand(),
                        command.cardLast4(), token, decision.declineReason(), now);
        txn = transactions.saveAndFlush(txn);

        String eventType = decision.approved() ? "AUTHORIZATION_APPROVED" : "AUTHORIZATION_DECLINED";
        events.save(new TransactionEvent(txn.getId(), eventType, null, txn.getStatus(), txn.getAmountMinor(),
                decision.approved() ? "Approved by simulator" : "Declined: " + decision.declineReason(), now));
        audit.record("TRANSACTION", txn.getId(), "TRANSACTION_CREATED", AuditService.OPERATOR,
                Map.of("externalReference", reference, "merchantCode", merchant.getMerchantCode(),
                        "amountMinor", txn.getAmountMinor(), "currency", txn.getCurrency()));
        audit.record("TRANSACTION", txn.getId(), eventType, AuditService.SYSTEM,
                decision.approved() ? Map.of("externalReference", reference)
                        : Map.of("externalReference", reference, "declineReason", decision.declineReason()));

        idempotency.remember(idempotencyKey, "AUTHORIZE", canonical, txn.getId());
        return new Result(txn, false);
    }

    // ------------------------------------------------------------------ capture

    @Transactional
    public Result capture(UUID id, String idempotencyKey) {
        var replay = idempotency.findReplay(idempotencyKey, "CAPTURE", id.toString());
        if (replay.isPresent()) {
            return new Result(load(replay.get()), true);
        }
        Transaction txn = lock(id);
        TransactionStatus before = txn.getStatus();
        Instant now = clock.now();

        // Fee terms are read and frozen at capture time.
        FeePlan plan = txn.getMerchant().getFeePlan();
        FeeCalculator.FeeBreakdown fee = FeeCalculator.calculate(txn.getAmountMinor(), plan.terms());

        txn.capture(fee.totalFeeMinor(), now); // throws INVALID_STATE_TRANSITION unless AUTHORIZED
        feeAssessments.save(new FeeAssessment(txn.getId(), plan, fee, now));

        // Dr Network Receivable (gross) / Cr Merchant Payable (net) / Cr Fee Revenue (fee)
        ledger.post(new Posting("CAPTURE", SOURCE_TRANSACTION, txn.getId(), txn.getMerchant().getId(),
                txn.getCurrency(), "Capture " + txn.getExternalReference(), List.of(
                Line.debit(Account.NETWORK_RECEIVABLE, fee.grossMinor()),
                Line.credit(Account.MERCHANT_PAYABLE, fee.netMinor()),
                Line.credit(Account.FEE_REVENUE, fee.totalFeeMinor()))));

        events.save(new TransactionEvent(txn.getId(), "CAPTURED", before, txn.getStatus(), txn.getAmountMinor(),
                "Fee " + fee.totalFeeMinor() + ", net " + fee.netMinor() + " (minor units)", now));
        audit.record("TRANSACTION", txn.getId(), "FEE_ASSESSED", AuditService.SYSTEM,
                Map.of("feePlan", plan.getCode(), "totalFeeMinor", fee.totalFeeMinor(),
                        "adjustment", fee.adjustment().name()));
        audit.record("TRANSACTION", txn.getId(), "TRANSACTION_CAPTURED", AuditService.OPERATOR,
                Map.of("externalReference", txn.getExternalReference(), "amountMinor", txn.getAmountMinor()));

        idempotency.remember(idempotencyKey, "CAPTURE", id.toString(), txn.getId());
        return new Result(txn, false);
    }

    // ------------------------------------------------------------------ reverse

    @Transactional
    public Result reverse(UUID id, String idempotencyKey) {
        var replay = idempotency.findReplay(idempotencyKey, "REVERSE", id.toString());
        if (replay.isPresent()) {
            return new Result(load(replay.get()), true);
        }
        Transaction txn = lock(id);
        TransactionStatus before = txn.getStatus();
        Instant now = clock.now();

        // An authorization moves no money, so releasing it needs no ledger entry.
        txn.reverse(now);
        events.save(new TransactionEvent(txn.getId(), "REVERSED", before, txn.getStatus(), txn.getAmountMinor(),
                "Authorization released", now));
        audit.record("TRANSACTION", txn.getId(), "TRANSACTION_REVERSED", AuditService.OPERATOR,
                Map.of("externalReference", txn.getExternalReference()));

        idempotency.remember(idempotencyKey, "REVERSE", id.toString(), txn.getId());
        return new Result(txn, false);
    }

    // ------------------------------------------------------------------- refund

    @Transactional
    public Result refund(UUID id, long amountMinor, String reason, String idempotencyKey) {
        String canonical = id + "|" + amountMinor + "|" + reason;
        var replay = idempotency.findReplay(idempotencyKey, "REFUND", canonical);
        if (replay.isPresent()) {
            Refund original = refunds.findById(replay.get())
                    .orElseThrow(() -> new IllegalStateException("Idempotency record points at a missing refund"));
            return new Result(load(original.getTransactionId()), true);
        }
        // The row lock makes the cumulative check below safe against concurrent refunds.
        Transaction txn = lock(id);
        TransactionStatus before = txn.getStatus();
        Instant now = clock.now();

        txn.refund(amountMinor, now); // validates state and the cumulative refundable amount

        Refund refund = refunds.saveAndFlush(new Refund(txn.getId(),
                "RFD-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT),
                amountMinor, txn.getCurrency(), reason, now));

        // Dr Merchant Payable / Cr Refund Clearing. The processing fee is not returned (documented policy).
        ledger.post(new Posting("REFUND", SOURCE_REFUND, refund.getId(), txn.getMerchant().getId(),
                txn.getCurrency(), "Refund " + refund.getRefundReference() + " of " + txn.getExternalReference(),
                List.of(Line.debit(Account.MERCHANT_PAYABLE, amountMinor),
                        Line.credit(Account.REFUND_CLEARING, amountMinor))));

        events.save(new TransactionEvent(txn.getId(), "REFUNDED", before, txn.getStatus(), amountMinor,
                reason == null || reason.isBlank() ? "Refund " + refund.getRefundReference() : reason, now));
        audit.record("TRANSACTION", txn.getId(), "TRANSACTION_REFUNDED", AuditService.OPERATOR,
                Map.of("externalReference", txn.getExternalReference(), "refundReference",
                        refund.getRefundReference(), "amountMinor", amountMinor,
                        "totalRefundedMinor", txn.getRefundedAmountMinor()));

        idempotency.remember(idempotencyKey, "REFUND", canonical, refund.getId());
        return new Result(txn, false);
    }

    // -------------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public Detail detail(UUID id) {
        Transaction txn = load(id);
        List<Refund> txnRefunds = refunds.findByTransactionIdOrderByCreatedAtAsc(id);
        List<JournalEntry> entries = new ArrayList<>(ledger.forSource(SOURCE_TRANSACTION, id));
        txnRefunds.forEach(refund -> entries.addAll(ledger.forSource(SOURCE_REFUND, refund.getId())));
        return new Detail(txn, events.findByTransactionIdOrderByCreatedAtAscIdAsc(id),
                feeAssessments.findByTransactionId(id).orElse(null), txnRefunds, entries);
    }

    @Transactional(readOnly = true)
    public Page<Transaction> search(SearchCriteria criteria, Pageable pageable) {
        Specification<Transaction> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (criteria.query() != null && !criteria.query().isBlank()) {
                String pattern = "%" + criteria.query().trim().toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                where.add(cb.like(cb.lower(root.get("externalReference")), pattern, '\\'));
            }
            if (criteria.merchantId() != null) {
                where.add(cb.equal(root.get("merchant").get("id"), criteria.merchantId()));
            }
            if (criteria.status() != null) {
                where.add(cb.equal(root.get("status"), criteria.status()));
            }
            if (criteria.currency() != null && !criteria.currency().isBlank()) {
                where.add(cb.equal(root.get("currency"), criteria.currency()));
            }
            if (criteria.from() != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("createdAt"), criteria.from()));
            }
            if (criteria.to() != null) {
                where.add(cb.lessThan(root.get("createdAt"), criteria.to()));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        return transactions.findAll(spec, pageable);
    }

    private Transaction load(UUID id) {
        return transactions.findDetailed(id).orElseThrow(() -> ApiException.notFound("Transaction", id));
    }

    private Transaction lock(UUID id) {
        return transactions.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Transaction", id));
    }
}
