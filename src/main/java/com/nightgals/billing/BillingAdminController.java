package com.nightgals.billing;

import com.nightgals.billing.dto.PurchaseResponse;
import com.nightgals.common.ErrorResponse;
import com.nightgals.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@Tag(name = "10. Billing (admin)", description = """
        Settling payments by hand, until a payment API is integrated.

        This is what makes the manual provider usable in production: money arrives
        on a till number or by transfer, someone reconciles it, and confirms the
        purchase here. `MODERATOR` and `ADMIN` only.
        """)
@RestController
@RequestMapping("/api/v1/admin/billing")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
public class BillingAdminController {

    private final BillingService billingService;

    @Operation(summary = "Purchases awaiting settlement, oldest first")
    @ApiResponse(responseCode = "200", description = "Pending purchases")
    @GetMapping("/purchases/pending")
    public PageResponse<PurchaseResponse> pending(@PageableDefault(size = 20) Pageable pageable) {
        return billingService.pending(pageable);
    }

    @Operation(summary = "How many purchases are waiting")
    @ApiResponse(responseCode = "200", description = "Pending count")
    @GetMapping("/purchases/pending/count")
    public Map<String, Long> pendingCount() {
        return Map.of("pending", billingService.pendingCount());
    }

    @Operation(
            summary = "Confirm a purchase as paid",
            description = """
                    Marks it `COMPLETED` and grants what it bought - the profile unlock, or
                    the subscription period.

                    Idempotent: confirming an already-completed purchase changes nothing, so
                    this is also the method a payment webhook should call.
                    """)
    @ApiResponse(responseCode = "200", description = "Settled and access granted")
    @ApiResponse(responseCode = "409", description = "Purchase is not pending",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/purchases/{purchaseId}/settle")
    public PurchaseResponse settle(@PathVariable UUID purchaseId,
                                   @Parameter(description = "The provider's own payment reference, e.g. an M-Pesa code")
                                   @RequestParam(required = false) String providerReference) {
        return billingService.settle(purchaseId, providerReference);
    }

    @Operation(summary = "Mark a purchase as failed")
    @ApiResponse(responseCode = "200", description = "Marked failed")
    @PostMapping("/purchases/{purchaseId}/fail")
    public PurchaseResponse fail(@PathVariable UUID purchaseId,
                                 @RequestParam(defaultValue = "Payment not received") String reason) {
        return billingService.fail(purchaseId, reason);
    }

    @Operation(summary = "Grant one item without payment",
            description = """
                    Comps, support gestures and testing. Recorded with source `GRANT`
                    rather than `PURCHASE`, so it is distinguishable later and produces no
                    earnings for the creator.

                    Per item, like everything else - granting a whole profile is no longer
                    a thing that can be expressed.
                    """)
    @ApiResponse(responseCode = "204", description = "Access granted")
    @PostMapping("/grants")
    public ResponseEntity<Void> grant(
            @Parameter(description = "Who receives the access", required = true) @RequestParam UUID viewerId,
            @Parameter(description = "Which item they may see", required = true) @RequestParam UUID mediaId) {
        billingService.grantMedia(viewerId, mediaId);
        return ResponseEntity.noContent().build();
    }
}
