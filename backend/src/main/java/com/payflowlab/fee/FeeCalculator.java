package com.payflowlab.fee;

/**
 * Pure fee arithmetic. Integer minor units only, no floating point anywhere.
 *
 * <p>Rounding policy (documented in docs/domain-model.md):
 * <ol>
 *   <li>percentage component = amount x bps / 10000, rounded half-up to the nearest minor unit</li>
 *   <li>raw fee = percentage component + fixed fee</li>
 *   <li>the plan minimum, then the plan maximum, are applied to the raw fee</li>
 *   <li>the fee is finally capped at the transaction amount, so net is never negative</li>
 * </ol>
 */
public final class FeeCalculator {

    private static final long BPS_DENOMINATOR = 10_000L;

    private FeeCalculator() {
    }

    public enum Adjustment {
        NONE, MINIMUM_APPLIED, MAXIMUM_APPLIED, CAPPED_AT_AMOUNT
    }

    public record FeeTerms(int percentageBps, long fixedFeeMinor, Long minFeeMinor, Long maxFeeMinor) {
    }

    public record FeeBreakdown(long grossMinor, long percentageComponentMinor, long fixedComponentMinor,
                               Adjustment adjustment, long totalFeeMinor, long netMinor) {
    }

    public static FeeBreakdown calculate(long amountMinor, FeeTerms terms) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        // multiplyExact: an overflow must fail loudly, never wrap into a plausible-looking fee
        long scaled = Math.multiplyExact(amountMinor, (long) terms.percentageBps());
        long percentage = Math.addExact(scaled, BPS_DENOMINATOR / 2) / BPS_DENOMINATOR;
        long fixed = terms.fixedFeeMinor();

        long total = Math.addExact(percentage, fixed);
        Adjustment adjustment = Adjustment.NONE;

        if (terms.minFeeMinor() != null && total < terms.minFeeMinor()) {
            total = terms.minFeeMinor();
            adjustment = Adjustment.MINIMUM_APPLIED;
        }
        if (terms.maxFeeMinor() != null && total > terms.maxFeeMinor()) {
            total = terms.maxFeeMinor();
            adjustment = Adjustment.MAXIMUM_APPLIED;
        }
        if (total > amountMinor) {
            total = amountMinor;
            adjustment = Adjustment.CAPPED_AT_AMOUNT;
        }
        return new FeeBreakdown(amountMinor, percentage, fixed, adjustment, total, amountMinor - total);
    }
}
