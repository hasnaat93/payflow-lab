package com.payflowlab.transaction;

import static com.payflowlab.transaction.TransactionStatus.AUTHORIZED;
import static com.payflowlab.transaction.TransactionStatus.CAPTURED;
import static com.payflowlab.transaction.TransactionStatus.DECLINED;
import static com.payflowlab.transaction.TransactionStatus.PARTIALLY_REFUNDED;
import static com.payflowlab.transaction.TransactionStatus.REFUNDED;
import static com.payflowlab.transaction.TransactionStatus.REVERSED;
import static org.assertj.core.api.Assertions.assertThat;

import com.payflowlab.transaction.AuthorizationSimulator.Decision;
import com.payflowlab.transaction.AuthorizationSimulator.Scenario;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Pure rules: the state machine and the deterministic simulator. No Spring, no database. */
class TransactionRulesTest {

    private static final Map<TransactionStatus, Set<TransactionStatus>> ALLOWED = Map.of(
            AUTHORIZED, EnumSet.of(CAPTURED, REVERSED),
            CAPTURED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED),
            PARTIALLY_REFUNDED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED),
            DECLINED, EnumSet.noneOf(TransactionStatus.class),
            REFUNDED, EnumSet.noneOf(TransactionStatus.class),
            REVERSED, EnumSet.noneOf(TransactionStatus.class));

    @Test
    void everyPairOfStatesIsEitherExplicitlyAllowedOrRejected() {
        for (TransactionStatus from : TransactionStatus.values()) {
            for (TransactionStatus to : TransactionStatus.values()) {
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(ALLOWED.get(from).contains(to));
            }
        }
    }

    @Test
    void declinedReversedAndRefundedAreTerminal() {
        assertThat(DECLINED.isTerminal()).isTrue();
        assertThat(REVERSED.isTerminal()).isTrue();
        assertThat(REFUNDED.isTerminal()).isTrue();
        assertThat(AUTHORIZED.isTerminal()).isFalse();
        assertThat(CAPTURED.isTerminal()).isFalse();
        assertThat(PARTIALLY_REFUNDED.isTerminal()).isFalse();
    }

    private final AuthorizationSimulator simulator = new AuthorizationSimulator(5_000_000);

    @ParameterizedTest(name = "last4 {0} -> {1}")
    @CsvSource({"0002, INSUFFICIENT_FUNDS", "0051, EXPIRED_CARD", "0119, SUSPECTED_FRAUD"})
    void magicCardNumbersDecline(String last4, String reason) {
        Decision decision = simulator.decide(1_000, last4, Scenario.AUTO);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.declineReason()).isEqualTo(reason);
    }

    @Test
    void ordinaryCardIsApproved() {
        assertThat(simulator.decide(1_000, "4242", Scenario.AUTO).approved()).isTrue();
    }

    @Test
    void amountLimitIsInclusive() {
        assertThat(simulator.decide(5_000_000, "4242", Scenario.AUTO).approved()).isTrue();

        Decision over = simulator.decide(5_000_001, "4242", Scenario.AUTO);
        assertThat(over.approved()).isFalse();
        assertThat(over.declineReason()).isEqualTo("AMOUNT_LIMIT_EXCEEDED");
    }

    @Test
    void forcedScenariosOverrideTheRules() {
        assertThat(simulator.decide(1_000, "4242", Scenario.FORCE_DECLINE).declineReason())
                .isEqualTo("SIMULATOR_FORCED_DECLINE");
        assertThat(simulator.decide(9_999_999, "0002", Scenario.FORCE_APPROVE).approved()).isTrue();
    }

    @Test
    void sameInputAlwaysGivesTheSameDecision() {
        for (int i = 0; i < 50; i++) {
            assertThat(simulator.decide(1_234, "0051", Scenario.AUTO)).isEqualTo(new Decision(false, "EXPIRED_CARD"));
            assertThat(simulator.decide(1_234, "7777", Scenario.AUTO)).isEqualTo(new Decision(true, null));
        }
    }
}
