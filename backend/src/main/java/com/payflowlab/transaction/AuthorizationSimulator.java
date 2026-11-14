package com.payflowlab.transaction;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stands in for the card network. Decisions are a pure function of the request,
 * with no randomness, so the same input always produces the same outcome and
 * tests are never flaky.
 *
 * <p>Rules, evaluated in order:
 * <ol>
 *   <li>scenario FORCE_DECLINE: declined (SIMULATOR_FORCED_DECLINE)</li>
 *   <li>scenario FORCE_APPROVE: approved, skipping the rules below</li>
 *   <li>amount above the configured limit: declined (AMOUNT_LIMIT_EXCEEDED)</li>
 *   <li>magic last-four digits: 0002 INSUFFICIENT_FUNDS, 0051 EXPIRED_CARD, 0119 SUSPECTED_FRAUD</li>
 *   <li>otherwise: approved</li>
 * </ol>
 */
@Component
public class AuthorizationSimulator {

    public enum Scenario {
        AUTO, FORCE_APPROVE, FORCE_DECLINE
    }

    public record Decision(boolean approved, String declineReason) {

        static Decision approve() {
            return new Decision(true, null);
        }

        static Decision decline(String reason) {
            return new Decision(false, reason);
        }
    }

    public record Rule(String trigger, String outcome) {
    }

    private static final Map<String, String> MAGIC_LAST4 = Map.of(
            "0002", "INSUFFICIENT_FUNDS",
            "0051", "EXPIRED_CARD",
            "0119", "SUSPECTED_FRAUD");

    private final long maxAmountMinor;

    public AuthorizationSimulator(@Value("${payflow.simulator.max-amount-minor}") long maxAmountMinor) {
        this.maxAmountMinor = maxAmountMinor;
    }

    public Decision decide(long amountMinor, String cardLast4, Scenario scenario) {
        if (scenario == Scenario.FORCE_DECLINE) {
            return Decision.decline("SIMULATOR_FORCED_DECLINE");
        }
        if (scenario == Scenario.FORCE_APPROVE) {
            return Decision.approve();
        }
        if (amountMinor > maxAmountMinor) {
            return Decision.decline("AMOUNT_LIMIT_EXCEEDED");
        }
        String magic = MAGIC_LAST4.get(cardLast4);
        return magic != null ? Decision.decline(magic) : Decision.approve();
    }

    /** Human-readable rule list, served to the UI so the simulator explains itself. */
    public List<Rule> rules() {
        return List.of(
                new Rule("Scenario FORCE_DECLINE", "Declined: SIMULATOR_FORCED_DECLINE"),
                new Rule("Scenario FORCE_APPROVE", "Approved (skips the rules below)"),
                new Rule("Amount above " + maxAmountMinor + " minor units", "Declined: AMOUNT_LIMIT_EXCEEDED"),
                new Rule("Card last four 0002", "Declined: INSUFFICIENT_FUNDS"),
                new Rule("Card last four 0051", "Declined: EXPIRED_CARD"),
                new Rule("Card last four 0119", "Declined: SUSPECTED_FRAUD"),
                new Rule("Anything else", "Approved"));
    }
}
