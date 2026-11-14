package com.payflowlab.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AppClockTest {

    private final AppClock clock = new AppClock();

    @Test
    void consecutiveReadingsAreStrictlyIncreasing() {
        Instant previous = clock.now();
        for (int i = 0; i < 10_000; i++) {
            Instant next = clock.now();
            assertThat(next).isAfter(previous);
            previous = next;
        }
    }

    @Test
    void pinnedTimeIsReturnedExactlyAndReleasedAfterwards() {
        Instant past = Instant.parse("2020-01-01T00:00:00Z");

        Instant inside = clock.at(past, clock::now);

        assertThat(inside).isEqualTo(past);
        assertThat(clock.now()).isAfter(Instant.parse("2025-01-01T00:00:00Z"));
    }

    @Test
    void nestedPinsRestoreTheOuterPin() {
        Instant outer = Instant.parse("2020-01-01T00:00:00Z");
        Instant inner = Instant.parse("2021-06-01T00:00:00Z");

        Instant afterInner = clock.at(outer, () -> {
            assertThat(clock.at(inner, clock::now)).isEqualTo(inner);
            return clock.now();
        });

        assertThat(afterInner).isEqualTo(outer);
    }
}
