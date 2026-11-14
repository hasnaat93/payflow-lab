package com.payflowlab.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflowlab.reconciliation.ReconciliationMatcher.Actual;
import com.payflowlab.reconciliation.ReconciliationMatcher.Expected;
import com.payflowlab.reconciliation.ReconciliationMatcher.Outcome;
import com.payflowlab.reconciliation.ReconciliationMatcher.Result;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ReconciliationMatcherTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);

    private static Expected batch(String reference, long amount) {
        return new Expected(UUID.randomUUID(), reference, "ACME", "USD", amount, DAY);
    }

    private static Actual line(String reference, long amount, LocalDate valueDate) {
        return new Actual(UUID.randomUUID(), reference, "ACME", "USD", amount, valueDate);
    }

    @Test
    void identicalRecordsMatch() {
        List<Result> results = ReconciliationMatcher.match(
                List.of(batch("STL-1", 10_000)), List.of(line("STL-1", 10_000, DAY.plusDays(1))));

        assertThat(results).singleElement().satisfies(r -> {
            assertThat(r.outcome()).isEqualTo(Outcome.MATCHED);
            assertThat(r.differenceMinor()).isZero();
        });
    }

    @Test
    void oneMinorUnitDifferenceIsAMismatchNotAMatch() {
        List<Result> results = ReconciliationMatcher.match(
                List.of(batch("STL-1", 10_000)), List.of(line("STL-1", 9_999, DAY.plusDays(1))));

        assertThat(results).singleElement().satisfies(r -> {
            assertThat(r.outcome()).isEqualTo(Outcome.AMOUNT_MISMATCH);
            assertThat(r.expectedMinor()).isEqualTo(10_000);
            assertThat(r.actualMinor()).isEqualTo(9_999);
            assertThat(r.differenceMinor()).isEqualTo(-1);
            assertThat(r.detail()).contains("short by 1");
        });
    }

    @Test
    void overpaymentIsReportedWithAPositiveDifference() {
        Result result = ReconciliationMatcher.match(
                List.of(batch("STL-1", 10_000)), List.of(line("STL-1", 10_250, DAY))).get(0);

        assertThat(result.outcome()).isEqualTo(Outcome.AMOUNT_MISMATCH);
        assertThat(result.differenceMinor()).isEqualTo(250);
        assertThat(result.detail()).contains("over by 250");
    }

    @Test
    void batchMissingFromStatementIsUnmatchedInternal() {
        List<Result> results = ReconciliationMatcher.match(List.of(batch("STL-1", 10_000)), List.of());

        assertThat(results).singleElement().satisfies(r -> {
            assertThat(r.outcome()).isEqualTo(Outcome.UNMATCHED_INTERNAL);
            assertThat(r.actualMinor()).isNull();
        });
    }

    @Test
    void statementLineWithUnknownReferenceIsUnmatchedExternal() {
        List<Result> results = ReconciliationMatcher.match(List.of(), List.of(line("STL-GHOST", 500, DAY)));

        assertThat(results).singleElement().satisfies(r -> {
            assertThat(r.outcome()).isEqualTo(Outcome.UNMATCHED_EXTERNAL);
            assertThat(r.batchId()).isNull();
            assertThat(r.expectedMinor()).isNull();
        });
    }

    @ParameterizedTest(name = "value date +{0} days -> {1}")
    @CsvSource({"-1, DATE_MISMATCH", "0, MATCHED", "1, MATCHED", "2, MATCHED", "3, DATE_MISMATCH"})
    void valueDateWindowBoundaries(int offsetDays, Outcome expected) {
        Result result = ReconciliationMatcher.match(
                List.of(batch("STL-1", 10_000)), List.of(line("STL-1", 10_000, DAY.plusDays(offsetDays)))).get(0);

        assertThat(result.outcome()).isEqualTo(expected);
    }

    /** Same reference and amount but a different merchant is a different payout: never a match. */
    @Test
    void sameReferenceForADifferentMerchantDoesNotMatch() {
        Actual wrongMerchant = new Actual(UUID.randomUUID(), "STL-1", "OTHER", "USD", 10_000, DAY);

        List<Result> results = ReconciliationMatcher.match(List.of(batch("STL-1", 10_000)), List.of(wrongMerchant));

        assertThat(results).extracting(Result::outcome)
                .containsExactlyInAnyOrder(Outcome.UNMATCHED_INTERNAL, Outcome.UNMATCHED_EXTERNAL);
    }

    @Test
    void sameReferenceInADifferentCurrencyDoesNotMatch() {
        Actual wrongCurrency = new Actual(UUID.randomUUID(), "STL-1", "ACME", "EUR", 10_000, DAY);

        List<Result> results = ReconciliationMatcher.match(List.of(batch("STL-1", 10_000)), List.of(wrongCurrency));

        assertThat(results).extracting(Result::outcome)
                .containsExactlyInAnyOrder(Outcome.UNMATCHED_INTERNAL, Outcome.UNMATCHED_EXTERNAL);
    }

    @Test
    void duplicateStatementLineMatchesOnceAndTheExtraIsAnException() {
        List<Result> results = ReconciliationMatcher.match(List.of(batch("STL-1", 10_000)),
                List.of(line("STL-1", 10_000, DAY), line("STL-1", 10_000, DAY)));

        assertThat(results).extracting(Result::outcome)
                .containsExactlyInAnyOrder(Outcome.MATCHED, Outcome.UNMATCHED_EXTERNAL);
    }

    @Test
    void amountIsCheckedBeforeDateSoTheMoreSeriousProblemIsReported() {
        Result result = ReconciliationMatcher.match(
                List.of(batch("STL-1", 10_000)), List.of(line("STL-1", 9_000, DAY.plusDays(9)))).get(0);

        assertThat(result.outcome()).isEqualTo(Outcome.AMOUNT_MISMATCH);
    }

    @Test
    void mixedSetProducesOneResultPerBatchAndPerLeftoverLine() {
        List<Result> results = ReconciliationMatcher.match(
                List.of(batch("A", 100), batch("B", 200), batch("C", 300)),
                List.of(line("A", 100, DAY), line("B", 150, DAY), line("Z", 999, DAY)));

        assertThat(results).hasSize(4);
        assertThat(results).filteredOn(r -> r.reference().equals("A")).extracting(Result::outcome)
                .containsExactly(Outcome.MATCHED);
        assertThat(results).filteredOn(r -> r.reference().equals("B")).extracting(Result::outcome)
                .containsExactly(Outcome.AMOUNT_MISMATCH);
        assertThat(results).filteredOn(r -> r.reference().equals("C")).extracting(Result::outcome)
                .containsExactly(Outcome.UNMATCHED_INTERNAL);
        assertThat(results).filteredOn(r -> r.reference().equals("Z")).extracting(Result::outcome)
                .containsExactly(Outcome.UNMATCHED_EXTERNAL);
    }
}
