package com.payflowlab.reporting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports")
public class ReportController {

    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Report catalogue")
    public List<ReportService.ReportDefinition> catalogue() {
        return service.catalogue();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Run a report",
            description = "Returns typed columns, rows and the exact SQL that produced them. "
                    + "Defaults: USD, last 30 days.")
    public ReportService.ReportResult run(@PathVariable String id,
                                          @RequestParam(required = false) String currency,
                                          @RequestParam(required = false) LocalDate from,
                                          @RequestParam(required = false) LocalDate to) {
        return service.run(id, currency, from, to);
    }

    @GetMapping(value = "/{id}/export", produces = "text/csv")
    @Operation(summary = "Download a report as CSV")
    public ResponseEntity<byte[]> export(@PathVariable String id,
                                         @RequestParam(required = false) String currency,
                                         @RequestParam(required = false) LocalDate from,
                                         @RequestParam(required = false) LocalDate to) {
        ReportService.ReportResult result = service.run(id, currency, from, to);
        String filename = "payflow-" + result.id() + "-" + result.currency() + "-" + result.from() + "_"
                + result.to() + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(service.toCsv(result).getBytes(StandardCharsets.UTF_8));
    }
}
