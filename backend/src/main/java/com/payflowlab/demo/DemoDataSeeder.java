package com.payflowlab.demo;

import com.payflowlab.common.AppClock;
import com.payflowlab.fee.FeePlan;
import com.payflowlab.fee.FeePlanService;
import com.payflowlab.merchant.Merchant;
import com.payflowlab.merchant.MerchantService;
import com.payflowlab.reconciliation.ReconciliationItem;
import com.payflowlab.reconciliation.ReconciliationService;
import com.payflowlab.settlement.SettlementService;
import com.payflowlab.transaction.AuthorizationSimulator;
import com.payflowlab.transaction.Transaction;
import com.payflowlab.transaction.TransactionService;
import com.payflowlab.transaction.TransactionStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Builds the demo dataset by replaying ten days of activity through the real
 * services with the clock pinned to the past. Nothing is inserted behind the
 * domain's back, so fees, ledger entries, settlements and audit events in the
 * demo are exactly what the application itself would have produced.
 *
 * <p>All merchants, references and amounts are fictional. The pseudo-random
 * generator has a fixed seed, so every reset produces the same shape of data.
 */
@Component
public class DemoDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final int HISTORY_DAYS = 10;
    private static final String[] BRANDS = {"VISA", "VISA", "MASTERCARD", "MASTERCARD", "VISA", "AMEX", "UNIONPAY"};
    private static final String[] MAGIC_DECLINES = {"0002", "0051", "0119"};

    private final FeePlanService feePlans;
    private final MerchantService merchants;
    private final TransactionService transactions;
    private final SettlementService settlement;
    private final ReconciliationService reconciliation;
    private final AppClock clock;
    private final JdbcTemplate jdbc;

    public DemoDataSeeder(FeePlanService feePlans, MerchantService merchants, TransactionService transactions,
                          SettlementService settlement, ReconciliationService reconciliation, AppClock clock,
                          JdbcTemplate jdbc) {
        this.feePlans = feePlans;
        this.merchants = merchants;
        this.transactions = transactions;
        this.settlement = settlement;
        this.reconciliation = reconciliation;
        this.clock = clock;
        this.jdbc = jdbc;
    }

    public boolean isEmpty() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM merchants", Integer.class);
        return count == null || count == 0;
    }

    /**
     * Removes all operational data and seeds a fresh demo. The chart of accounts
     * is reference data and is kept. TRUNCATE is used deliberately: it is the one
     * statement the ledger's append-only row triggers do not block, and it is
     * only reachable through this explicitly demo-gated path.
     */
    public synchronized void reset() {
        wipe();
        seed();
    }

    /** Empties every operational table, keeping the schema and the chart of accounts. */
    public synchronized void wipe() {
        jdbc.execute("""
                TRUNCATE TABLE idempotency_records, audit_events, reconciliation_items, reconciliation_runs,
                               external_statement_lines, external_statements, settlement_batch_lines,
                               journal_lines, journal_entries, fee_assessments, transaction_events, refunds,
                               transactions, settlement_batches, merchants, fee_plans
                """);
        jdbc.execute("ALTER SEQUENCE journal_entry_number_seq RESTART WITH 1000");
    }

    public synchronized void seed() {
        long started = System.currentTimeMillis();
        Random random = new Random(20_240_601L);
        Instant realNow = clock.now();
        LocalDate today = LocalDate.ofInstant(realNow, ZoneOffset.UTC);
        Instant setupTime = at(today.minusDays(HISTORY_DAYS + 1), 9, 0);

        // ---- fee plans and merchants ---------------------------------------
        record Seeded(Merchant merchant, long minAmount, long maxAmount, int baseVolume) {
        }
        List<Seeded> active = clock.at(setupTime, () -> {
            FeePlan standardUsd = feePlans.create("STANDARD-USD", "Standard (2.90% + 0.30)", "USD", 290, 30, null, null);
            FeePlan premiumUsd = feePlans.create("PREMIUM-USD", "Premium (2.20% + 0.25, min 0.50)", "USD", 220, 25, 50L, null);
            FeePlan enterpriseUsd = feePlans.create("ENTERPRISE-USD", "Enterprise (1.60% + 0.10, max 25.00)", "USD", 160, 10, null, 2500L);
            FeePlan standardPkr = feePlans.create("STANDARD-PKR", "Standard PKR (2.50% + 15.00)", "PKR", 250, 1500, null, null);
            FeePlan standardEur = feePlans.create("STANDARD-EUR", "Standard EUR (2.40% + 0.25)", "EUR", 240, 25, null, null);

            List<Seeded> list = new ArrayList<>();
            list.add(new Seeded(merchants.create("NORTHWIND-COFFEE", "Northwind Coffee Roasters",
                    "Northwind Coffee Roasters LLC (fictional)", "USD", standardUsd.getId()), 350, 4_800, 11));
            list.add(new Seeded(merchants.create("LUMEN-BOOKS", "Lumen Books",
                    "Lumen Books Inc. (fictional)", "USD", premiumUsd.getId()), 900, 16_000, 8));
            list.add(new Seeded(merchants.create("ATLAS-OUTDOOR", "Atlas Outdoor Supply",
                    "Atlas Outdoor Supply Co. (fictional)", "USD", enterpriseUsd.getId()), 4_000, 240_000, 6));
            list.add(new Seeded(merchants.create("KARAKORAM-TEA", "Karakoram Tea House",
                    "Karakoram Tea House (Pvt) Ltd (fictional)", "PKR", standardPkr.getId()), 45_000, 650_000, 9));
            list.add(new Seeded(merchants.create("INDUS-ELECTRO", "Indus Electronics",
                    "Indus Electronics (Pvt) Ltd (fictional)", "PKR", standardPkr.getId()), 350_000, 5_600_000, 6));
            list.add(new Seeded(merchants.create("HALCYON-STUDIO", "Halcyon Design Studio",
                    "Halcyon Design Studio GmbH (fictional)", "EUR", standardEur.getId()), 2_500, 90_000, 5));
            // a suspended merchant, so the status filter and the suspension rule have something to show
            Merchant dormant = merchants.create("DORMANT-GOODS", "Dormant Goods Co.",
                    "Dormant Goods Co. (fictional)", "USD", standardUsd.getId());
            merchants.changeStatus(dormant.getId(), Merchant.Status.SUSPENDED);
            return list;
        });

        // ---- ten days of payments, settled at the end of each day ---------
        int sequence = 0;
        List<Transaction> refundCandidates = new ArrayList<>();
        Transaction halcyonCapture = null;

        for (int daysAgo = HISTORY_DAYS; daysAgo >= 0; daysAgo--) {
            LocalDate day = today.minusDays(daysAgo);
            boolean isToday = daysAgo == 0;

            // refunds for some of the previous day's captures arrive the next morning
            for (Transaction candidate : refundCandidates) {
                boolean partial = random.nextBoolean();
                long amount = partial ? Math.max(1, candidate.getCapturedAmountMinor() / (2 + random.nextInt(3)))
                        : candidate.getCapturedAmountMinor();
                String key = "seed-refund-" + candidate.getExternalReference();
                clock.at(notAfter(at(day, 7, 15 + random.nextInt(40)), realNow), () -> transactions.refund(candidate.getId(), amount,
                        partial ? "Partial refund: item returned" : "Order cancelled by customer", key));
            }
            refundCandidates.clear();

            for (Seeded seeded : active) {
                String code = seeded.merchant().getMerchantCode();
                // Halcyon has no sales on day -8: its only activity is a refund, which makes
                // that day's settlement net negative and demonstrates a FAILED batch.
                if (code.equals("HALCYON-STUDIO") && daysAgo == HISTORY_DAYS - 2) {
                    if (halcyonCapture != null) {
                        Transaction target = halcyonCapture;
                        clock.at(at(day, 11, 0), () -> transactions.refund(target.getId(),
                                target.getCapturedAmountMinor(), "Project cancelled", "seed-refund-halcyon"));
                    }
                    continue;
                }
                int volume = seeded.baseVolume() + random.nextInt(4) - (day.getDayOfWeek().getValue() >= 6 ? 2 : 0);
                if (isToday) {
                    volume = Math.max(2, volume / 2); // today is still in progress
                }
                boolean leftOneOpen = false;
                for (int i = 0; i < volume; i++) {
                    sequence++;
                    int roll = random.nextInt(100);
                    long span = seeded.maxAmount() - seeded.minAmount();
                    // squaring the fraction skews amounts toward the low end, like real basket sizes
                    double fraction = Math.pow(random.nextDouble(), 2);
                    long amount = seeded.minAmount() + (long) (span * fraction);
                    String last4 = roll < 8
                            ? MAGIC_DECLINES[random.nextInt(MAGIC_DECLINES.length)]
                            : String.format("%04d", 1000 + random.nextInt(8999));
                    Instant when = at(day, 8 + (i * 12 / Math.max(1, volume)), random.nextInt(60));
                    if (isToday && when.isAfter(realNow.minusSeconds(900))) {
                        // nothing in the demo may be timestamped in the future
                        when = realNow.minusSeconds(900L + 120L * (volume - i));
                    }
                    String reference = String.format("PAY-%s-%05d", day.toString().replace("-", ""), sequence);
                    Instant authTime = when;
                    Transaction txn = clock.at(authTime, () -> transactions.authorize(
                            new TransactionService.AuthorizeCommand(seeded.merchant().getId(), amount,
                                    seeded.merchant().getCurrency(), BRANDS[random.nextInt(BRANDS.length)],
                                    last4, reference, AuthorizationSimulator.Scenario.AUTO),
                            "seed-auth-" + reference).transaction());
                    if (txn.getStatus() != TransactionStatus.AUTHORIZED) {
                        continue;
                    }
                    if (roll >= 8 && roll < 14) {
                        clock.at(authTime.plusSeconds(600), () -> transactions.reverse(txn.getId(), null));
                        continue;
                    }
                    if (isToday && (roll >= 90 || !leftOneOpen)) {
                        // Leave some of today's authorizations open so there is something to capture:
                        // always the first for each merchant, so this never depends on the day's volume.
                        leftOneOpen = true;
                        continue;
                    }
                    Transaction captured = clock.at(authTime.plusSeconds(90),
                            () -> transactions.capture(txn.getId(), null).transaction());
                    if (code.equals("HALCYON-STUDIO") && daysAgo == HISTORY_DAYS - 1 && halcyonCapture == null) {
                        halcyonCapture = captured;
                    } else if (roll >= 14 && roll < 21 && !isToday) {
                        refundCandidates.add(captured);
                    }
                }
            }

            // Days up to the day before yesterday are settled. Yesterday and today are left
            // pending so the "Run settlement" button has real work to do in the demo.
            if (daysAgo >= 2) {
                LocalDate settlementDay = day;
                clock.at(at(day, 23, 30), () -> settlement.run(settlementDay, null));
            }
        }

        // ---- reconciliation history ----------------------------------------
        LocalDate first = today.minusDays(HISTORY_DAYS);
        clock.at(at(today.minusDays(4), 6, 0), () -> {
            var statement = reconciliation.generateStatement(first, today.minusDays(6),
                    ReconciliationService.StatementScenario.WITH_ANOMALIES);
            var run = reconciliation.run(statement.getId());
            run.items().stream()
                    .filter(item -> item.getReviewStatus() == ReconciliationItem.ReviewStatus.OPEN)
                    .findFirst()
                    .ifPresent(item -> reconciliation.review(item.getId(),
                            "Confirmed with the settlement bank; correction expected on the next statement."));
            return run;
        });
        clock.at(at(today.minusDays(1), 6, 0), () -> {
            var statement = reconciliation.generateStatement(today.minusDays(5), today.minusDays(3),
                    ReconciliationService.StatementScenario.CLEAN);
            return reconciliation.run(statement.getId());
        });

        log.info("Demo data seeded: {} synthetic payments in {} ms", sequence, System.currentTimeMillis() - started);
    }

    private static Instant at(LocalDate day, int hour, int minute) {
        return day.atTime(Math.min(hour, 23), Math.min(minute, 59)).toInstant(ZoneOffset.UTC);
    }

    private static Instant notAfter(Instant candidate, Instant limit) {
        return candidate.isAfter(limit) ? limit : candidate;
    }
}
