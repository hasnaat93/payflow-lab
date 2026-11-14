package com.payflowlab.merchant;

import com.payflowlab.common.PageResponse;
import com.payflowlab.common.Paging;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/merchants")
@Tag(name = "Merchants")
public class MerchantController {

    private static final Set<String> SORTABLE = Set.of("displayName", "merchantCode", "createdAt", "status");

    private final MerchantService service;

    public MerchantController(MerchantService service) {
        this.service = service;
    }

    public record MerchantResponse(UUID id, String merchantCode, String displayName, String legalName,
                                   String currency, String status, UUID feePlanId, String feePlanCode,
                                   String feePlanName, Instant createdAt, Instant updatedAt) {
        public static MerchantResponse from(Merchant m) {
            return new MerchantResponse(m.getId(), m.getMerchantCode(), m.getDisplayName(), m.getLegalName(),
                    m.getCurrency(), m.getStatus().name(), m.getFeePlan().getId(), m.getFeePlan().getCode(),
                    m.getFeePlan().getName(), m.getCreatedAt(), m.getUpdatedAt());
        }
    }

    public record CreateMerchantRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9-]{3,32}$", message = "3-32 chars: A-Z, 0-9 or -")
            String merchantCode,
            @NotBlank @Size(max = 120) String displayName,
            @Size(max = 160) String legalName,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO currency code")
            String currency,
            @NotNull UUID feePlanId) {
    }

    public record UpdateMerchantRequest(
            @NotBlank @Size(max = 120) String displayName,
            @Size(max = 160) String legalName,
            @NotNull UUID feePlanId) {
    }

    @GetMapping
    @Operation(summary = "List and search merchants")
    public PageResponse<MerchantResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Merchant.Status status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return PageResponse.of(service.search(q, status, Paging.of(page, size, sort, SORTABLE, "createdAt")),
                MerchantResponse::from);
    }

    @GetMapping("/{id}")
    public MerchantResponse get(@PathVariable UUID id) {
        return MerchantResponse.from(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a fictional merchant")
    public MerchantResponse create(@Valid @RequestBody CreateMerchantRequest request) {
        return MerchantResponse.from(service.create(request.merchantCode(), request.displayName(),
                request.legalName(), request.currency(), request.feePlanId()));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a merchant's names and fee plan")
    public MerchantResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateMerchantRequest request) {
        return MerchantResponse.from(
                service.update(id, request.displayName(), request.legalName(), request.feePlanId()));
    }

    @PostMapping("/{id}/activate")
    public MerchantResponse activate(@PathVariable UUID id) {
        return MerchantResponse.from(service.changeStatus(id, Merchant.Status.ACTIVE));
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Suspend a merchant: new authorizations are rejected, existing money still settles")
    public MerchantResponse suspend(@PathVariable UUID id) {
        return MerchantResponse.from(service.changeStatus(id, Merchant.Status.SUSPENDED));
    }
}
