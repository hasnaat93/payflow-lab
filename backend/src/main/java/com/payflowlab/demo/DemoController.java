package com.payflowlab.demo;

import com.payflowlab.common.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demo")
@Tag(name = "Demo")
public class DemoController implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final DemoDataSeeder seeder;
    private final boolean seedOnStartup;
    private final boolean resetEnabled;

    public DemoController(DemoDataSeeder seeder,
                          @Value("${payflow.demo.seed-on-startup}") boolean seedOnStartup,
                          @Value("${payflow.demo.reset-enabled}") boolean resetEnabled) {
        this.seeder = seeder;
        this.seedOnStartup = seedOnStartup;
        this.resetEnabled = resetEnabled;
    }

    public record DemoStatus(boolean simulator, boolean resetEnabled, String notice) {
    }

    /** First start against an empty database loads the demo so the dashboard is meaningful immediately. */
    @Override
    public void run(ApplicationArguments args) {
        if (seedOnStartup && seeder.isEmpty()) {
            log.info("Empty database detected: seeding fictional demo data");
            seeder.seed();
        }
    }

    @GetMapping("/status")
    @Operation(summary = "Simulator notice and whether demo reset is allowed in this environment")
    public DemoStatus status() {
        return new DemoStatus(true, resetEnabled,
                "PayFlow Lab is an educational simulator. All merchants, cards, references and amounts are "
                        + "fictional. It is not connected to any card network and must not be used for real payments.");
    }

    @PostMapping("/reset")
    @Operation(summary = "Wipe all operational data and reload the fictional demo dataset",
            description = "Only available when DEMO_RESET_ENABLED=true. Takes a few seconds.")
    public DemoStatus reset() {
        if (!resetEnabled) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEMO_RESET_DISABLED",
                    "Demo reset is disabled in this environment");
        }
        seeder.reset();
        return status();
    }
}
