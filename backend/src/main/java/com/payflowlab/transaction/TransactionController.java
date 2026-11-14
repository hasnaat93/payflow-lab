package com.payflowlab.transaction;

import com.payflowlab.common.PageResponse;
import com.payflowlab.common.Paging;
import com.payflowlab.fee.FeeAssessment;
import com.payflowlab.ledger.LedgerController.JournalEntryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Transactions")
public class TransactionController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String REPLAY_HEADER = "Idempotent-Replay";
    private static final Set<String> SORTABLE = Set.of("createdAt", "amountMinor", "status");

    private final TransactionService service;
    private final AuthorizationSimulator simulator;

    public TransactionController(TransactionService service, AuthorizationSimulator simulator) {
        this.service = service;
        this.simulator = simulator;
    }

    /**
     * Deliberately has no field for a card number, expiry, CVV or PIN. The only
     * card data the API accepts is a brand and four digits. Unknown properties are
     * rejected (see spring.jackson.deserialization in application.yml), so a client
     * that tries to send a "cardNumber" gets a 400 instead of having it silently dropped.
     */
    public record AuthorizeRequest(
            @NotNull UUID merchantId,
            @NotNull @Positive Long amountMinor,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO currency code")
            String currency,
            @NotBlank @Pattern(regexp = "^(VISA|MASTERCARD|AMEX|UNIONPAY)$",
                    message = "must be VISA, MASTERCARD, AMEX or UNIONPAY") String cardBrand,
            @NotBlank @Pattern(regexp = "^[0-9]{4}$", message = "must be exactly 4 digits") String cardLast4,
            @Pattern(regexp = "^[A-Za-z0-9._-]{6,64}$", message = "6-64 chars: letters, digits, . _ -")
            String externalReference,
            AuthorizationSimulator.Scenario scenario) {
    }

    public record RefundRequest(@NotNull @Positive Long amountMinor, @Size(max = 200) String reason) {
    }

    public record TransactionResponse(UUID id, String externalReference, UUID merchantId, String merchantCode,
                                      String merchantName, long amountMinor, String currency, String status,
                                      String cardBrand, String cardLast4, String declineReason,
                                      long capturedAmountMinor, long refundedAmountMinor, long refundableMinor,
                                      long feeMinor, long netMinor, Instant capturedAt, UUID settlementBatchId,
                                      Instant createdAt, Instant updatedAt) {
        public static TransactionResponse from(Transaction t) {
            return new TransactionResponse(t.getId(), t.getExternalReference(), t.getMerchant().getId(),
                    t.getMerchant().getMerchantCode(), t.getMerchant().getDisplayName(), t.getAmountMinor(),
                    t.getCurrency(), t.getStatus().name(), t.getCardBrand(), t.getCardLast4(),
                    t.getDeclineReason(), t.getCapturedAmountMinor(), t.getRefundedAmountMinor(),
                    t.refundableMinor(), t.getFeeMinor(),
                    t.getCapturedAmountMinor() - t.getFeeMinor() - t.getRefundedAmountMinor(), t.getCapturedAt(),
                    t.getSettlementBatchId(), t.getCreatedAt(), t.getUpdatedAt());
        }
    }

    public record EventResponse(UUID id, String eventType, String fromStatus, String toStatus, Long amountMinor,
                                String detail, Instant createdAt) {
    }

    public record FeeAssessmentResponse(String planCode, String currency, long grossMinor, int percentageBps,
                                        long fixedFeeMinor, Long minFeeMinor, Long maxFeeMinor,
                                        long percentageComponentMinor, long fixedComponentMinor, String adjustment,
                                        long totalFeeMinor, long netMinor, Instant createdAt) {
        static FeeAssessmentResponse from(FeeAssessment f) {
            return f == null ? null : new FeeAssessmentResponse(f.getPlanCode(), f.getCurrency(), f.getGrossMinor(),
                    f.getPercentageBps(), f.getFixedFeeMinor(), f.getMinFeeMinor(), f.getMaxFeeMinor(),
                    f.getPercentageComponentMinor(), f.getFixedComponentMinor(), f.getAdjustment().name(),
                    f.getTotalFeeMinor(), f.getNetMinor(), f.getCreatedAt());
        }
    }

    public record RefundResponse(UUID id, String refundReference, long amountMinor, String currency, String reason,
                                 UUID settlementBatchId, Instant createdAt) {
    }

    public record TransactionDetailResponse(TransactionResponse transaction, List<EventResponse> events,
                                            FeeAssessmentResponse feeAssessment, List<RefundResponse> refunds,
                                            List<JournalEntryResponse> journalEntries) {
    }

    @GetMapping("/transactions")
    @Operation(summary = "Search transactions")
    public PageResponse<TransactionResponse> search(
            @Parameter(description = "Matches part of the external reference") @RequestParam(required = false)
            String q,
            @RequestParam(required = false) UUID merchantId,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return PageResponse.of(
                service.search(new TransactionService.SearchCriteria(q, merchantId, status, currency, from, to),
                        Paging.of(page, size, sort, SORTABLE, "createdAt")),
                TransactionResponse::from);
    }

    @GetMapping("/transactions/{id}")
    @Operation(summary = "Transaction with its history, fee breakdown, refunds and ledger entries")
    public TransactionDetailResponse get(@PathVariable UUID id) {
        TransactionService.Detail d = service.detail(id);
        return new TransactionDetailResponse(
                TransactionResponse.from(d.transaction()),
                d.events().stream().map(e -> new EventResponse(e.getId(), e.getEventType(),
                        e.getFromStatus() == null ? null : e.getFromStatus().name(), e.getToStatus().name(),
                        e.getAmountMinor(), e.getDetail(), e.getCreatedAt())).toList(),
                FeeAssessmentResponse.from(d.feeAssessment()),
                d.refunds().stream().map(r -> new RefundResponse(r.getId(), r.getRefundReference(),
                        r.getAmountMinor(), r.getCurrency(), r.getReason(), r.getSettlementBatchId(),
                        r.getCreatedAt())).toList(),
                d.journalEntries().stream().map(JournalEntryResponse::from).toList());
    }

    @PostMapping("/transactions")
    @Operation(summary = "Request an authorization",
            description = "The simulator approves or declines deterministically. A decline is a successful "
                    + "API call (201) that creates a DECLINED transaction. Idempotency-Key is required.")
    public ResponseEntity<TransactionResponse> authorize(
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody AuthorizeRequest request) {
        TransactionService.Result result = service.authorize(
                new TransactionService.AuthorizeCommand(request.merchantId(), request.amountMinor(),
                        request.currency(), request.cardBrand(), request.cardLast4(), request.externalReference(),
                        request.scenario()),
                IdempotencyService.validateKey(idempotencyKey));
        return respond(result, HttpStatus.CREATED);
    }

    @PostMapping("/transactions/{id}/capture")
    @Operation(summary = "Capture an authorized transaction (assesses the fee and posts to the ledger)")
    public ResponseEntity<TransactionResponse> capture(
            @PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
        return respond(service.capture(id, IdempotencyService.validateKey(idempotencyKey)), HttpStatus.OK);
    }

    @PostMapping("/transactions/{id}/reverse")
    @Operation(summary = "Reverse (void) an authorization that has not been captured")
    public ResponseEntity<TransactionResponse> reverse(
            @PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
        return respond(service.reverse(id, IdempotencyService.validateKey(idempotencyKey)), HttpStatus.OK);
    }

    @PostMapping("/transactions/{id}/refunds")
    @Operation(summary = "Refund all or part of a captured transaction",
            description = "Cumulative refunds can never exceed the captured amount. Idempotency-Key is required.")
    public ResponseEntity<TransactionResponse> refund(
            @PathVariable UUID id,
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody RefundRequest request) {
        return respond(service.refund(id, request.amountMinor(), request.reason(),
                IdempotencyService.validateKey(idempotencyKey)), HttpStatus.CREATED);
    }

    @GetMapping("/simulator/rules")
    @Operation(summary = "The deterministic approval rules, in evaluation order")
    public List<AuthorizationSimulator.Rule> simulatorRules() {
        return simulator.rules();
    }

    private ResponseEntity<TransactionResponse> respond(TransactionService.Result result, HttpStatus fresh) {
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : fresh)
                .header(REPLAY_HEADER, String.valueOf(result.replayed()))
                .body(TransactionResponse.from(result.transaction()));
    }
}
