package com.payflowlab.ledger;

import com.payflowlab.common.PageResponse;
import com.payflowlab.common.Paging;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ledger")
@Tag(name = "Ledger")
public class LedgerController {

    private final LedgerService ledger;

    public LedgerController(LedgerService ledger) {
        this.ledger = ledger;
    }

    public record JournalLineResponse(int lineNo, String accountCode, String accountName, String side,
                                      long amountMinor) {
    }

    public record JournalEntryResponse(UUID id, Long entryNumber, String eventType, String sourceType,
                                       UUID sourceId, UUID merchantId, String currency, String description,
                                       Instant postedAt, long totalMinor, List<JournalLineResponse> lines) {
        public static JournalEntryResponse from(JournalEntry e) {
            return new JournalEntryResponse(e.getId(), e.getEntryNumber(), e.getEventType(), e.getSourceType(),
                    e.getSourceId(), e.getMerchantId(), e.getCurrency(), e.getDescription(), e.getPostedAt(),
                    e.totalMinor(),
                    e.getLines().stream().map(l -> new JournalLineResponse(l.getLineNo(), l.getAccount().getCode(),
                            l.getAccount().getName(), l.getSide().name(), l.getAmountMinor())).toList());
        }
    }

    @GetMapping("/accounts")
    @Operation(summary = "Chart of accounts with balances derived from journal lines, per currency")
    public List<LedgerService.AccountBalance> accounts() {
        return ledger.balances();
    }

    @GetMapping("/journal-entries")
    @Operation(summary = "Search journal entries")
    public PageResponse<JournalEntryResponse> entries(
            @RequestParam(required = false) String accountCode,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) UUID merchantId,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return PageResponse.of(
                ledger.search(accountCode, eventType, merchantId, currency, from, to,
                        Paging.of(page, size, null, Set.of("postedAt"), "postedAt")),
                JournalEntryResponse::from);
    }

    @GetMapping("/journal-entries/{id}")
    public JournalEntryResponse entry(@PathVariable UUID id) {
        return JournalEntryResponse.from(ledger.get(id));
    }
}
