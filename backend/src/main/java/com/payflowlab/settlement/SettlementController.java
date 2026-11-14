package com.payflowlab.settlement;

import com.payflowlab.common.PageResponse;
import com.payflowlab.common.Paging;
import com.payflowlab.ledger.LedgerController.JournalEntryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settlement-batches")
@Tag(name = "Settlement")
public class SettlementController {

    private static final Set<String> SORTABLE = Set.of("createdAt", "settlementDate", "netMinor", "status");

    private final SettlementService service;

    public SettlementController(SettlementService service) {
        this.service = service;
    }

    public record RunSettlementRequest(LocalDate settlementDate, UUID merchantId) {
    }

    public record BatchResponse(UUID id, String batchReference, UUID merchantId, String merchantCode,
                                String merchantName, String currency, LocalDate settlementDate, String status,
                                int transactionCount, int refundCount, long grossMinor, long feeMinor,
                                long refundMinor, long netMinor, String summary, String failureReason,
                                Instant startedAt, Instant completedAt, Instant createdAt) {
        public static BatchResponse from(SettlementBatch b) {
            return new BatchResponse(b.getId(), b.getBatchReference(), b.getMerchant().getId(),
                    b.getMerchant().getMerchantCode(), b.getMerchant().getDisplayName(), b.getCurrency(),
                    b.getSettlementDate(), b.getStatus().name(), b.getTransactionCount(), b.getRefundCount(),
                    b.getGrossMinor(), b.getFeeMinor(), b.getRefundMinor(), b.getNetMinor(), b.getSummary(),
                    b.getFailureReason(), b.getStartedAt(), b.getCompletedAt(), b.getCreatedAt());
        }
    }

    public record RunResponse(LocalDate settlementDate, int merchantsConsidered, int completed, int failed,
                              long transactionsSettled, String summary, List<BatchResponse> batches) {
    }

    public record BatchDetailResponse(BatchResponse batch, List<SettlementService.BatchLineView> lines,
                                      List<JournalEntryResponse> journalEntries) {
    }

    @GetMapping
    @Operation(summary = "List settlement batches")
    public PageResponse<BatchResponse> list(
            @RequestParam(required = false) UUID merchantId,
            @RequestParam(required = false) SettlementBatch.Status status,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return PageResponse.of(
                service.search(merchantId, status, from, to, Paging.of(page, size, sort, SORTABLE, "createdAt")),
                BatchResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Batch with its lines and ledger entries")
    public BatchDetailResponse get(@PathVariable UUID id) {
        SettlementService.BatchDetail d = service.detail(id);
        return new BatchDetailResponse(BatchResponse.from(d.batch()), d.lines(),
                d.journalEntries().stream().map(JournalEntryResponse::from).toList());
    }

    @PostMapping("/run")
    @Operation(summary = "Run settlement",
            description = "Settles every captured transaction and refund not yet settled, up to the end of the "
                    + "settlement date (default: today, UTC). Running it again settles only what is new.")
    public RunResponse run(@RequestBody(required = false) RunSettlementRequest request) {
        SettlementService.RunResult r = service.run(
                request == null ? null : request.settlementDate(),
                request == null ? null : request.merchantId());
        return new RunResponse(r.settlementDate(), r.merchantsConsidered(), r.completed(), r.failed(),
                r.transactionsSettled(), r.summary(), r.batches().stream().map(BatchResponse::from).toList());
    }
}
