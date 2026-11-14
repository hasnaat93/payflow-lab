package com.payflowlab.fee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflowlab.fee.FeeCalculator.Adjustment;
import com.payflowlab.fee.FeeCalculator.FeeBreakdown;
import com.payflowlab.fee.FeeCalculator.FeeTerms;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FeeCalculatorTest {

    private static final FeeTerms STANDARD = new FeeTerms(290, 30, null, null); // 2.90% + 0.30

    @Test
    void percentagePlusFixed() {
        FeeBreakdown fee = FeeCalculator.calculate(10_000, STANDARD); // 100.00

        assertThat(fee.percentageComponentMinor()).isEqualTo(290);
        assertThat(fee.fixedComponentMinor()).isEqualTo(30);
        assertThat(fee.totalFeeMinor()).isEqualTo(320);
        assertThat(fee.netMinor()).isEqualTo(9_680);
        assertThat(fee.adjustment()).isEqualTo(Adjustment.NONE);
    }

    /** 1.00% of the amount lands exactly on, just below and just above a half minor unit. */
    @ParameterizedTest(name = "1% of {0} rounds to {1}")
    @CsvSource({
            "149, 1",   // 1.49 -> 1 (below half: down)
            "150, 2",   // 1.50 -> 2 (exactly half: up)
            "151, 2",   // 1.51 -> 2
            "49,  0",   // 0.49 -> 0
            "50,  1",   // 0.50 -> 1
            "1,   0",   // 0.01 -> 0
            "250, 3",   // 2.50 -> 3
            "249, 2",   // 2.49 -> 2
    })
    void percentageRoundsHalfUp(long amountMinor, long expectedPercentage) {
        FeeBreakdown fee = FeeCalculator.calculate(amountMinor, new FeeTerms(100, 0, null, null));

        assertThat(fee.percentageComponentMinor()).isEqualTo(expectedPercentage);
    }

    @Test
    void minimumFeeIsApplied() {
        FeeBreakdown fee = FeeCalculator.calculate(1_000, new FeeTerms(100, 0, 50L, null)); // raw fee 10

        assertThat(fee.totalFeeMinor()).isEqualTo(50);
        assertThat(fee.adjustment()).isEqualTo(Adjustment.MINIMUM_APPLIED);
        assertThat(fee.netMinor()).isEqualTo(950);
    }

    @Test
    void feeExactlyAtMinimumIsNotAdjusted() {
        FeeBreakdown fee = FeeCalculator.calculate(5_000, new FeeTerms(100, 0, 50L, null)); // raw fee 50

        assertThat(fee.totalFeeMinor()).isEqualTo(50);
        assertThat(fee.adjustment()).isEqualTo(Adjustment.NONE);
    }

    @Test
    void maximumFeeIsApplied() {
        FeeBreakdown fee = FeeCalculator.calculate(1_000_000, new FeeTerms(160, 10, null, 2_500L)); // raw 16010

        assertThat(fee.totalFeeMinor()).isEqualTo(2_500);
        assertThat(fee.adjustment()).isEqualTo(Adjustment.MAXIMUM_APPLIED);
    }

    @Test
    void feeNeverExceedsTheAmountSoNetIsNeverNegative() {
        FeeBreakdown fee = FeeCalculator.calculate(20, STANDARD); // fixed fee alone (30) exceeds the amount

        assertThat(fee.totalFeeMinor()).isEqualTo(20);
        assertThat(fee.netMinor()).isZero();
        assertThat(fee.adjustment()).isEqualTo(Adjustment.CAPPED_AT_AMOUNT);
    }

    @Test
    void minimumAboveAmountIsStillCappedAtAmount() {
        FeeBreakdown fee = FeeCalculator.calculate(10, new FeeTerms(0, 0, 50L, null));

        assertThat(fee.totalFeeMinor()).isEqualTo(10);
        assertThat(fee.adjustment()).isEqualTo(Adjustment.CAPPED_AT_AMOUNT);
    }

    @Test
    void zeroRatePlanChargesNothing() {
        FeeBreakdown fee = FeeCalculator.calculate(12_345, new FeeTerms(0, 0, null, null));

        assertThat(fee.totalFeeMinor()).isZero();
        assertThat(fee.netMinor()).isEqualTo(12_345);
    }

    @Test
    void grossAlwaysEqualsFeePlusNet() {
        for (long amount = 1; amount <= 5_000; amount += 7) {
            FeeBreakdown fee = FeeCalculator.calculate(amount, new FeeTerms(275, 25, 40L, 900L));
            assertThat(fee.totalFeeMinor() + fee.netMinor()).isEqualTo(amount);
            assertThat(fee.totalFeeMinor()).isBetween(0L, amount);
        }
    }

    @Test
    void rejectsNonPositiveAmounts() {
        assertThatThrownBy(() -> FeeCalculator.calculate(0, STANDARD)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeeCalculator.calculate(-5, STANDARD)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overflowFailsLoudlyInsteadOfWrapping() {
        assertThatThrownBy(() -> FeeCalculator.calculate(Long.MAX_VALUE, new FeeTerms(10_000, 0, null, null)))
                .isInstanceOf(ArithmeticException.class);
    }
}
