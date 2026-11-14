package com.payflowlab.reconciliation;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Compares what the platform says it paid out (settlement batches) with what
 * the external statement says was paid. Pure and deterministic: no database,
 * no clock, no tolerance on amounts.
 *
 * <p>Rules, applied per batch reference:
 * <ol>
 *   <li>No statement line with that reference: UNMATCHED_INTERNAL.</li>
 *   <li>A line exists but its merchant or currency differs: it is not the same payout.
 *       The batch is UNMATCHED_INTERNAL and the line is UNMATCHED_EXTERNAL.</li>
 *   <li>Amounts differ by even one minor unit: AMOUNT_MISMATCH.</li>
 *   <li>Value date outside settlement date .. settlement date + 2 days: DATE_MISMATCH.</li>
 *   <li>Otherwise: MATCHED.</li>
 * </ol>
 * Statement lines left over (unknown or duplicated references) are UNMATCHED_EXTERNAL.
 * Nothing is ever rounded, netted or "close enough" into a match.
 */
public final class ReconciliationMatcher {

    public static final int VALUE_DATE_WINDOW_DAYS = 2;

    private ReconciliationMatcher() {
    }

    public enum Outcome {
        MATCHED, AMOUNT_MISMATCH, DATE_MISMATCH, UNMATCHED_INTERNAL, UNMATCHED_EXTERNAL
    }

    /** A completed settlement batch: what we expect to see on the statement. */
    public record Expected(UUID batchId, String reference, String merchantCode, String currency, long amountMinor,
                           LocalDate settlementDate) {
    }

    /** A line from the external statement: what actually arrived. */
    public record Actual(UUID lineId, String reference, String merchantCode, String currency, long amountMinor,
                         LocalDate valueDate) {
    }

    public record Result(Outcome outcome, String reference, String merchantCode, String currency, UUID batchId,
                         UUID lineId, Long expectedMinor, Long actualMinor, Long differenceMinor, String detail) {
    }

    public static List<Result> match(List<Expected> expected, List<Actual> actual) {
        Map<String, Actual> byReference = new HashMap<>();
        List<Actual> leftovers = new ArrayList<>();
        for (Actual line : actual) {
            // a reference can pair with one batch only; a repeat is an exception, not a second match
            if (byReference.putIfAbsent(line.reference(), line) != null) {
                leftovers.add(line);
            }
        }

        List<Result> results = new ArrayList<>();
        for (Expected batch : expected) {
            Actual line = byReference.remove(batch.reference());
            if (line == null) {
                results.add(internalOnly(batch, "No statement line carries this batch reference"));
            } else if (!line.merchantCode().equals(batch.merchantCode())
                    || !line.currency().equals(batch.currency())) {
                results.add(internalOnly(batch, "Statement line with this reference is for "
                        + line.merchantCode() + " in " + line.currency() + ", not the same payout"));
                results.add(externalOnly(line, "Reference matches a batch for " + batch.merchantCode() + " in "
                        + batch.currency() + ", but merchant or currency differs"));
            } else if (line.amountMinor() != batch.amountMinor()) {
                long difference = line.amountMinor() - batch.amountMinor();
                results.add(paired(Outcome.AMOUNT_MISMATCH, batch, line, "Statement amount is "
                        + (difference > 0 ? "over" : "short") + " by " + Math.abs(difference) + " minor units"));
            } else if (!withinWindow(batch.settlementDate(), line.valueDate())) {
                results.add(paired(Outcome.DATE_MISMATCH, batch, line, "Value date " + line.valueDate()
                        + " is outside " + batch.settlementDate() + " to "
                        + batch.settlementDate().plusDays(VALUE_DATE_WINDOW_DAYS)));
            } else {
                results.add(paired(Outcome.MATCHED, batch, line,
                        "Reference, merchant, currency, amount and value date all agree"));
            }
        }

        leftovers.addAll(byReference.values());
        leftovers.stream()
                .sorted((a, b) -> a.reference().compareTo(b.reference()))
                .forEach(line -> results.add(externalOnly(line, "No completed settlement batch has this reference")));
        return results;
    }

    private static boolean withinWindow(LocalDate settlementDate, LocalDate valueDate) {
        long days = ChronoUnit.DAYS.between(settlementDate, valueDate);
        return days >= 0 && days <= VALUE_DATE_WINDOW_DAYS;
    }

    private static Result paired(Outcome outcome, Expected batch, Actual line, String detail) {
        return new Result(outcome, batch.reference(), batch.merchantCode(), batch.currency(), batch.batchId(),
                line.lineId(), batch.amountMinor(), line.amountMinor(), line.amountMinor() - batch.amountMinor(),
                detail);
    }

    private static Result internalOnly(Expected batch, String detail) {
        return new Result(Outcome.UNMATCHED_INTERNAL, batch.reference(), batch.merchantCode(), batch.currency(),
                batch.batchId(), null, batch.amountMinor(), null, null, detail);
    }

    private static Result externalOnly(Actual line, String detail) {
        return new Result(Outcome.UNMATCHED_EXTERNAL, line.reference(), line.merchantCode(), line.currency(), null,
                line.lineId(), null, line.amountMinor(), null, detail);
    }
}
