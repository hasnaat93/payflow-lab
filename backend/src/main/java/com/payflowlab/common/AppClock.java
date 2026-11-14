package com.payflowlab.common;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Single source of "now" for the domain. Every timestamp the application
 * writes comes from here, which keeps time testable and lets the demo seeder
 * replay several days of history through the real services.
 */
@Component
public class AppClock {

    private final ThreadLocal<Instant> override = new ThreadLocal<>();
    private final AtomicReference<Instant> last = new AtomicReference<>(Instant.EPOCH);

    /**
     * Current instant in UTC, truncated to microseconds (PostgreSQL's precision)
     * and strictly increasing within this process. The system clock can return
     * the same value for two calls made within a tick (commonly ~1 ms on Windows);
     * without this guarantee two events of one payment could share a timestamp
     * and their history would have no defined order.
     */
    public Instant now() {
        Instant pinned = override.get();
        if (pinned != null) {
            return pinned;
        }
        Instant wall = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return last.updateAndGet(previous -> wall.isAfter(previous) ? wall : previous.plus(1, ChronoUnit.MICROS));
    }

    /** Runs {@code work} with the clock pinned to {@code instant} on this thread only. */
    public <T> T at(Instant instant, Supplier<T> work) {
        Instant previous = override.get();
        override.set(instant.truncatedTo(ChronoUnit.MICROS));
        try {
            return work.get();
        } finally {
            if (previous == null) {
                override.remove();
            } else {
                override.set(previous);
            }
        }
    }
}
