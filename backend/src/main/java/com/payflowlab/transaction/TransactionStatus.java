package com.payflowlab.transaction;

import java.util.Set;

/**
 * The payment state machine. The transition table lives here, in one place,
 * and every state change in the service layer goes through {@link #canTransitionTo}.
 *
 * <pre>
 *   (new) ──► AUTHORIZED ──► CAPTURED ──► PARTIALLY_REFUNDED ──► REFUNDED
 *     │           │              └───────────────────────────────► REFUNDED
 *     │           └──► REVERSED
 *     └──► DECLINED
 * </pre>
 */
public enum TransactionStatus {
    AUTHORIZED,
    DECLINED,
    CAPTURED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    REVERSED;

    private Set<TransactionStatus> next() {
        return switch (this) {
            case AUTHORIZED -> Set.of(CAPTURED, REVERSED);
            case CAPTURED -> Set.of(PARTIALLY_REFUNDED, REFUNDED);
            case PARTIALLY_REFUNDED -> Set.of(PARTIALLY_REFUNDED, REFUNDED);
            // terminal states: nothing may follow a decline, a reversal or a full refund
            case DECLINED, REFUNDED, REVERSED -> Set.of();
        };
    }

    public boolean canTransitionTo(TransactionStatus target) {
        return next().contains(target);
    }

    public boolean isTerminal() {
        return next().isEmpty();
    }
}
