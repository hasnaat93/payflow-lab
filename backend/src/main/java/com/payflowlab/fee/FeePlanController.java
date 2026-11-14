package com.payflowlab.fee;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
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
@RequestMapping("/api/v1/fee-plans")
@Tag(name = "Fee plans")
public class FeePlanController {

    private final FeePlanService service;

    public FeePlanController(FeePlanService service) {
        this.service = service;
    }

    public record FeePlanResponse(UUID id, String code, String name, String currency, int percentageBps,
                                  long fixedFeeMinor, Long minFeeMinor, Long maxFeeMinor, boolean active,
                                  Instant createdAt, Instant updatedAt) {
        public static FeePlanResponse from(FeePlan p) {
            return new FeePlanResponse(p.getId(), p.getCode(), p.getName(), p.getCurrency(), p.getPercentageBps(),
                    p.getFixedFeeMinor(), p.getMinFeeMinor(), p.getMaxFeeMinor(), p.isActive(), p.getCreatedAt(),
                    p.getUpdatedAt());
        }
    }

    public record CreateFeePlanRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9_-]{3,40}$", message = "3-40 chars: A-Z, 0-9, _ or -") String code,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO currency code") String currency,
            @NotNull @Min(0) @Max(10000) Integer percentageBps,
            @NotNull @PositiveOrZero Long fixedFeeMinor,
            @PositiveOrZero Long minFeeMinor,
            @PositiveOrZero Long maxFeeMinor) {
    }

    public record UpdateFeePlanRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull @Min(0) @Max(10000) Integer percentageBps,
            @NotNull @PositiveOrZero Long fixedFeeMinor,
            @PositiveOrZero Long minFeeMinor,
            @PositiveOrZero Long maxFeeMinor,
            Boolean active) {
    }

    public record FeePreviewResponse(long grossMinor, long percentageComponentMinor, long fixedComponentMinor,
                                     String adjustment, long totalFeeMinor, long netMinor) {
    }

    @GetMapping
    @Operation(summary = "List fee plans")
    public List<FeePlanResponse> list() {
        return service.list().stream().map(FeePlanResponse::from).toList();
    }

    @GetMapping("/{id}")
    public FeePlanResponse get(@PathVariable UUID id) {
        return FeePlanResponse.from(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a fee plan")
    public FeePlanResponse create(@Valid @RequestBody CreateFeePlanRequest request) {
        return FeePlanResponse.from(service.create(request.code(), request.name(), request.currency(),
                request.percentageBps(), request.fixedFeeMinor(), request.minFeeMinor(), request.maxFeeMinor()));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a fee plan's terms (affects future captures only)")
    public FeePlanResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateFeePlanRequest request) {
        return FeePlanResponse.from(service.update(id, request.name(), request.percentageBps(),
                request.fixedFeeMinor(), request.minFeeMinor(), request.maxFeeMinor(), request.active()));
    }

    @GetMapping("/{id}/preview")
    @Operation(summary = "Preview the fee this plan would charge on an amount, without recording anything")
    public FeePreviewResponse preview(@PathVariable UUID id, @RequestParam @Min(1) long amountMinor) {
        FeeCalculator.FeeBreakdown b = FeeCalculator.calculate(amountMinor, service.get(id).terms());
        return new FeePreviewResponse(b.grossMinor(), b.percentageComponentMinor(), b.fixedComponentMinor(),
                b.adjustment().name(), b.totalFeeMinor(), b.netMinor());
    }
}
