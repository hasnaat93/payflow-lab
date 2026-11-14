package com.payflowlab.reconciliation;

import com.payflowlab.common.PageResponse;
import com.payflowlab.common.Paging;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reconciliation")
@Tag(name = "Reconciliation")
public class ReconciliationController {

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    public record GenerateStatementRequest(@NotNull LocalDate periodStart, @NotNull LocalDate periodEnd,
                                           ReconciliationService.StatementScenario scenario) {
    }

    public record StatementResponse(UUID id, String statementReference, String source, LocalDate periodStart,
                                    LocalDate periodEnd, int lineCount, Instant createdAt) {
        static StatementResponse from(ExternalStatement s) {
            return new StatementResponse(s.getId(), s.getStatementReference(), s.getSource(), s.getPeriodStart(),
                    s.getPeriodEnd(), s.getLineCount(), s.getCreatedAt());
        }
    }

    public record StatementLineResponse(UUID id, String batchReference, String merchantCode, String currency,
                                        long amountMinor, LocalDate valueDate) {
    }

    public record RunRequest(@NotNull UUID statementId) {
    }

    public record RunResponse(UUID id, UUID statementId, String statementReference, LocalDate periodStart,
                              LocalDate periodEnd, String status, int totalItems, int matchedCount,
                              int amountMismatchCount, int dateMismatchCount, int unmatchedInternalCount,
                              int unmatchedExternalCount, int exceptionCount, double matchRatePercent,
                              Instant startedAt, Instant completedAt) {
        static RunResponse from(ReconciliationRun r) {
            double rate = r.getTotalItems() == 0 ? 0
                    : Math.round(r.getMatchedCount() * 10000.0 / r.getTotalItems()) / 100.0;
            return new RunResponse(r.getId(), r.getStatement().getId(), r.getStatement().getStatementReference(),
                    r.getStatement().getPeriodStart(), r.getStatement().getPeriodEnd(), r.getStatus(),
                    r.getTotalItems(), r.getMatchedCount(), r.getAmountMismatchCount(), r.getDateMismatchCount(),
                    r.getUnmatchedInternalCount(), r.getUnmatchedExternalCount(), r.exceptionCount(), rate,
                    r.getStartedAt(), r.getCompletedAt());
        }
    }

    public record ItemResponse(UUID id, UUID runId, String outcome, String batchReference, String merchantCode,
                               String currency, UUID batchId, Long expectedMinor, Long actualMinor,
                               Long differenceMinor, String detail, String reviewStatus, String resolutionNote,
                               Instant reviewedAt) {
        static ItemResponse from(ReconciliationItem i) {
            return new ItemResponse(i.getId(), i.getRunId(), i.getOutcome().name(), i.getBatchReference(),
                    i.getMerchantCode(), i.getCurrency(), i.getBatchId(), i.getExpectedMinor(), i.getActualMinor(),
                    i.getDifferenceMinor(), i.getDetail(), i.getReviewStatus().name(), i.getResolutionNote(),
                    i.getReviewedAt());
        }
    }

    public record RunDetailResponse(RunResponse run, List<ItemResponse> items) {
        static RunDetailResponse from(ReconciliationService.RunDetail d) {
            return new RunDetailResponse(RunResponse.from(d.run()),
                    d.items().stream().map(ItemResponse::from).toList());
        }
    }

    public record ReviewRequest(@NotBlank @Size(min = 5, max = 500) String resolutionNote) {
    }

    @PostMapping("/statements/generate")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Generate a synthetic external statement from completed batches",
            description = "Scenario CLEAN mirrors the batches exactly. WITH_ANOMALIES (default) injects "
                    + "deterministic defects so each exception type can be demonstrated.")
    public StatementResponse generate(@Valid @RequestBody GenerateStatementRequest request) {
        return StatementResponse.from(service.generateStatement(request.periodStart(), request.periodEnd(),
                request.scenario() == null ? ReconciliationService.StatementScenario.WITH_ANOMALIES
                        : request.scenario()));
    }

    @GetMapping("/statements")
    public List<StatementResponse> statements() {
        return service.listStatements().stream().map(StatementResponse::from).toList();
    }

    @GetMapping("/statements/{id}/lines")
    public List<StatementLineResponse> statementLines(@PathVariable UUID id) {
        return service.statementLines(id).stream().map(l -> new StatementLineResponse(l.getId(),
                l.getBatchReference(), l.getMerchantCode(), l.getCurrency(), l.getAmountMinor(),
                l.getValueDate())).toList();
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Reconcile a statement against the completed batches of its period")
    public RunDetailResponse run(@Valid @RequestBody RunRequest request) {
        return RunDetailResponse.from(service.run(request.statementId()));
    }

    @GetMapping("/runs")
    @Operation(summary = "Reconciliation run history")
    public PageResponse<RunResponse> runs(@RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(service.listRuns(Paging.of(page, size, null, Set.of("startedAt"), "startedAt")),
                RunResponse::from);
    }

    @GetMapping("/runs/{id}")
    public RunDetailResponse runDetail(@PathVariable UUID id) {
        return RunDetailResponse.from(service.getRun(id));
    }

    @GetMapping("/exceptions")
    @Operation(summary = "Exception queue: every item that did not match")
    public PageResponse<ItemResponse> exceptions(
            @RequestParam(required = false) ReconciliationItem.ReviewStatus reviewStatus,
            @RequestParam(required = false) UUID runId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return PageResponse.of(
                service.exceptions(reviewStatus, runId,
                        Paging.of(page, size, "batchReference,asc", Set.of("batchReference"), "batchReference")),
                ItemResponse::from);
    }

    @PostMapping("/exceptions/{id}/review")
    @Operation(summary = "Mark an exception as reviewed with a resolution note (the outcome itself never changes)")
    public ItemResponse review(@PathVariable UUID id, @Valid @RequestBody ReviewRequest request) {
        return ItemResponse.from(service.review(id, request.resolutionNote()));
    }
}
