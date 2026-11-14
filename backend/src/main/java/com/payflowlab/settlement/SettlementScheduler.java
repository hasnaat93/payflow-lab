package com.payflowlab.settlement;

import com.payflowlab.common.AppClock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Optional end-of-day trigger, off by default (SETTLEMENT_SCHEDULE_ENABLED=true to turn on).
 * It settles the previous UTC day and contains no logic of its own; it calls the
 * same service method as the operator's "Run settlement" button.
 */
@Component
@ConditionalOnProperty(name = "payflow.settlement.schedule.enabled", havingValue = "true")
public class SettlementScheduler {

    private static final Logger log = LoggerFactory.getLogger(SettlementScheduler.class);

    private final SettlementService settlement;
    private final AppClock clock;

    public SettlementScheduler(SettlementService settlement, AppClock clock) {
        this.settlement = settlement;
        this.clock = clock;
    }

    @Scheduled(cron = "${payflow.settlement.schedule.cron}", zone = "UTC")
    public void settlePreviousDay() {
        LocalDate previousDay = LocalDate.ofInstant(clock.now(), ZoneOffset.UTC).minusDays(1);
        log.info("Scheduled settlement: {}", settlement.run(previousDay, null).summary());
    }
}
