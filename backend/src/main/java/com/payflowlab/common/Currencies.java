package com.payflowlab.common;

import java.util.Set;

/**
 * Currencies the simulator supports. All three use two minor-unit digits, so
 * "minor units" always means 1/100 of the major unit. Zero- or three-decimal
 * currencies (JPY, KWD) are intentionally out of scope and rejected.
 */
public final class Currencies {

    public static final Set<String> SUPPORTED = Set.of("USD", "EUR", "PKR");

    private Currencies() {
    }

    public static String require(String currency) {
        if (currency == null || !SUPPORTED.contains(currency)) {
            throw ApiException.badRequest("UNSUPPORTED_CURRENCY",
                    "Currency must be one of " + String.join(", ", SUPPORTED.stream().sorted().toList()));
        }
        return currency;
    }
}
