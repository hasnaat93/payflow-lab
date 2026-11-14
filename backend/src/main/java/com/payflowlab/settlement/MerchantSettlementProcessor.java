package com.payflowlab.settlement;

import com.payflowlab.audit.AuditService;
import com.payflowlab.common.AppClock;
import com.payflowlab.ledger.Account;
import com.payflowlab.ledger.LedgerService;
import com.payflowlab.ledger.LedgerService.Line;
import com.payflowlab.ledger.LedgerService.Posting;
import com.payflowlab.merchant.Merchant;
import com.payflowlab.merchant.MerchantRepository;
import com.payflowlab.settlement.SettlementRepositories.SettlementBatchLineRepository;
import com.payflowlab.settlement.SettlementRepositories.SettlementBatchRepository;
import com.payflowlab.transaction.Refund;
import com.payflowlab.transaction.Transaction;
import com.payflowlab.transaction.TransactionRepositories.RefundRepository;
import com.payflowlab.transaction.TransactionRepositories.TransactionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settles one merchant in one database transaction. Either the batch, every
 * batch line, every "settled" marker and both ledger entries are committed, or
 * none of them are.
 *
 * <p>Duplicate-settlement protection is layered:
 * <ol>
 *   <li>a row lock on the merchant serialises concurrent runs for that merchant</li>
 *   <li>eligible transactions are selected {@code FOR UPDATE} and filtered on "not yet settled"</li>
 *   <li>partial unique indexes on batch lines reject a second line for the same capture or refund</li>
 * </ol>
 */
@Component
public class MerchantSettlementProcessor {

    public static final String SOURCE_BATCH = "SETTLEMENT_BATCH";
    private static final DateTimeFormatter REF_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final MerchantRepository merchants;
    private final TransactionRepository transactions;
    private final RefundRepository refunds;
    private final SettlementBatchRepository batches;
    private final SettlementBatchLineRepository lines;
    private final LedgerService ledger;
    private final AuditService audit;
    private final AppClock clock;

    public MerchantSettlementProcessor(MerchantRepository merchants, TransactionRepository transactions,
                                       RefundRepository refunds, SettlementBatchRepository batches,
                                       SettlementBatchLineRepository lines, LedgerService ledger,
                                       AuditService audit, AppClock clock) {
        this.merchants = merchants;
        this.transactions = transactions;
        this.refunds = refunds;
        this.batches = batches;
        this.lines = lines;
        this.ledger = ledger;
        this.audit = audit;
        this.clock = clock;
    }

    /** @return the batch created for this merchant, or empty when nothing was eligible. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<SettlementBatch> settle(UUID merchantId, LocalDate settlementDate, Instant cutoff) {
        Merchant merchant = merchants.findForSettlement(merchantId)
                .orElseThrow(() -> new IllegalStateException("Merchant " + merchantId + " disappeared"));

        List<Transaction> captures = transactions.findEligibleForSettlement(merchantId, cutoff);
        List<Refund> pendingRefunds = refunds.findEligibleForSettlement(merchantId, cutoff);
        if (captures.isEmpty() && pendingRefunds.isEmpty()) {
            return Optional.empty();
        }

        long gross = captures.stream().mapToLong(Transaction::getCapturedAmountMinor).sum();
        long fees = captures.stream().mapToLong(Transaction::getFeeMinor).sum();
        long refunded = pendingRefunds.stream().mapToLong(Refund::getAmountMinor).sum();
        long net = gross - fees - refunded;

        Instant now = clock.now();
        SettlementBatch batch = new SettlementBatch(nextReference(merchant, settlementDate), merchant,
                settlementDate, now);
        batch.start(now);
        batch.totals(captures.size(), pendingRefunds.size(), gross, fees, refunded);

        if (net <= 0) {
            // A payout cannot be zero or negative. Record why, settle nothing, and let the
            // items net against future captures in a later run.
            batch.fail("NET_NOT_POSITIVE: refunds and fees (" + (fees + refunded)
                    + ") are not covered by captures (" + gross + ") for this period", now);
            batch = batches.save(batch);
            audit.record("SETTLEMENT_BATCH", batch.getId(), "SETTLEMENT_BATCH_FAILED", AuditService.SYSTEM,
                    Map.of("batchReference", batch.getBatchReference(), "netMinor", net));
            return Optional.of(batch);
        }

        batch = batches.saveAndFlush(batch);
        UUID batchId = batch.getId();

        List<SettlementBatchLine> batchLines = new ArrayList<>(captures.size() + pendingRefunds.size());
        for (Transaction txn : captures) {
            txn.markSettled(batchId, now);
            batchLines.add(SettlementBatchLine.capture(batchId, txn.getId(), txn.getCapturedAmountMinor(),
                    txn.getFeeMinor()));
        }
        for (Refund refund : pendingRefunds) {
            refund.markSettled(batchId);
            batchLines.add(SettlementBatchLine.refund(batchId, refund.getTransactionId(), refund.getId(),
                    refund.getAmountMinor()));
        }
        lines.saveAll(batchLines);

        // Dr Merchant Payable / Cr Settlement Clearing: the net payout leaves for the merchant's bank.
        ledger.post(new Posting("SETTLEMENT_PAYOUT", SOURCE_BATCH, batchId, merchantId, batch.getCurrency(),
                "Payout " + batch.getBatchReference(), List.of(
                Line.debit(Account.MERCHANT_PAYABLE, net),
                Line.credit(Account.SETTLEMENT_CLEARING, net))));
        // Dr Refund Clearing / Cr Network Receivable: refunds are netted against what the network owes.
        if (refunded > 0) {
            ledger.post(new Posting("REFUND_SETTLEMENT", SOURCE_BATCH, batchId, merchantId, batch.getCurrency(),
                    "Refunds cleared in " + batch.getBatchReference(), List.of(
                    Line.debit(Account.REFUND_CLEARING, refunded),
                    Line.credit(Account.NETWORK_RECEIVABLE, refunded))));
        }

        batch.complete("Settled " + captures.size() + " capture(s) and " + pendingRefunds.size()
                + " refund(s): gross " + gross + ", fees " + fees + ", refunds " + refunded + ", net " + net
                + " " + batch.getCurrency() + " minor units.", clock.now());
        audit.record("SETTLEMENT_BATCH", batchId, "SETTLEMENT_BATCH_COMPLETED", AuditService.SYSTEM,
                Map.of("batchReference", batch.getBatchReference(), "transactionCount", captures.size(),
                        "refundCount", pendingRefunds.size(), "netMinor", net, "currency", batch.getCurrency()));
        return Optional.of(batch);
    }

    /** Records an unexpected failure in its own transaction, after the failed attempt has rolled back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SettlementBatch recordFailure(UUID merchantId, LocalDate settlementDate, String reason) {
        Merchant merchant = merchants.findForSettlement(merchantId)
                .orElseThrow(() -> new IllegalStateException("Merchant " + merchantId + " disappeared"));
        Instant now = clock.now();
        SettlementBatch batch = new SettlementBatch(nextReference(merchant, settlementDate), merchant,
                settlementDate, now);
        batch.start(now);
        batch.fail("PROCESSING_ERROR: " + reason, now);
        batch = batches.save(batch);
        audit.record("SETTLEMENT_BATCH", batch.getId(), "SETTLEMENT_BATCH_FAILED", AuditService.SYSTEM,
                Map.of("batchReference", batch.getBatchReference(), "reason", reason));
        return batch;
    }

    /** Safe under the merchant row lock: no other transaction can be numbering this merchant's batches. */
    private String nextReference(Merchant merchant, LocalDate date) {
        long sequence = batches.countForMerchantAndDate(merchant.getId(), date) + 1;
        return "STL-" + REF_DATE.format(date) + "-" + merchant.getMerchantCode() + "-"
                + String.format("%02d", sequence);
    }
}
