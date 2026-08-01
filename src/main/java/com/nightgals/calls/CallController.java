package com.nightgals.calls;

import com.nightgals.billing.dto.CheckoutResponse;
import com.nightgals.calls.dto.BookCallRequest;
import com.nightgals.calls.dto.CallRateRequest;
import com.nightgals.calls.dto.CallRateResponse;
import com.nightgals.calls.dto.CallResponse;
import com.nightgals.common.ErrorResponse;
import com.nightgals.common.PageResponse;
import com.nightgals.user.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "13. Private calls", description = """
        1-to-1 video calls, priced by the creator.

        She publishes a price per length - 5, 10, 15, 30, 45 or 60 minutes - and offers
        whichever of those she wants. A viewer picks one, picks a time, and pays.

        **The platform does not carry the video.** It owns who may join, what it costs,
        when it happens, and that she is never double-booked. `GET /calls/{id}/room`
        hands back the provider's room, to the two participants only, once it is paid
        for and close enough to the start to be worth opening.
        """)
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CallController {

    private final CallService callService;

    // ------------------------------------------------------------ rates

    @Operation(summary = "What a creator charges for a call",
            description = "Her published price list. Public - it is what somebody decides on.",
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "200", description = "Active rates, shortest first")
    @GetMapping("/members/{userId}/call-rates")
    public List<CallRateResponse> ratesOf(@PathVariable UUID userId) {
        return callService.ratesOf(userId);
    }

    @Operation(summary = "The lengths I am allowed to price",
            description = "The platform's set. Offer whichever of them you want.")
    @ApiResponse(responseCode = "200", description = "Allowed durations in minutes")
    @GetMapping("/me/call-rates/durations")
    public Map<String, List<Integer>> allowedDurations() {
        return Map.of("durations", callService.allowedDurations());
    }

    @Operation(summary = "My own price list")
    @ApiResponse(responseCode = "200", description = "My active rates")
    @GetMapping("/me/call-rates")
    public List<CallRateResponse> myRates(@AuthenticationPrincipal AuthUser principal) {
        return callService.ratesOf(principal.id());
    }

    @Operation(summary = "Price one length, or withdraw it",
            description = """
                    A null `priceMinor` withdraws that length: you stop offering it, and
                    bookings already made against it are undisturbed.
                    """)
    @ApiResponse(responseCode = "200", description = "Rate saved")
    @ApiResponse(responseCode = "400", description = "Unsupported length, or a price outside the bounds",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PutMapping("/me/call-rates")
    public CallRateResponse setRate(@AuthenticationPrincipal AuthUser principal,
                                    @Valid @RequestBody CallRateRequest request) {
        return callService.setRate(principal.user(), request);
    }

    // ------------------------------------------------------------ booking

    @Operation(summary = "Book a private call",
            description = """
                    Holds the slot immediately and starts the payment. The slot is held
                    before the money lands, because the alternative lets two people pay for
                    the same time and leaves her to disappoint one of them.

                    Refused if she is already booked for any part of that window - overlap,
                    not just an identical start time.
                    """)
    @ApiResponse(responseCode = "200", description = "Booked; follow the payment instructions")
    @ApiResponse(responseCode = "400", description = "She does not offer that length, or the time is out of bounds",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "She is already booked then",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/members/{userId}/calls")
    public CheckoutResponse book(@AuthenticationPrincipal AuthUser principal,
                                 @PathVariable UUID userId,
                                 @Valid @RequestBody BookCallRequest request) {
        return callService.book(principal.user(), userId, request);
    }

    @Operation(summary = "My calls, booked and past")
    @ApiResponse(responseCode = "200", description = "Calls on either side, newest first")
    @GetMapping("/me/calls")
    public PageResponse<CallResponse> myCalls(@AuthenticationPrincipal AuthUser principal,
                                              @PageableDefault(size = 20) Pageable pageable) {
        return callService.myCalls(principal.user(), pageable);
    }

    @Operation(summary = "Call one off",
            description = "Either side may. The creator declining and the viewer cancelling "
                    + "are recorded differently, and both free the slot.")
    @ApiResponse(responseCode = "200", description = "Called off")
    @DeleteMapping("/calls/{callId}")
    public CallResponse cancel(@AuthenticationPrincipal AuthUser principal,
                               @PathVariable UUID callId,
                               @RequestParam(required = false) String reason) {
        return callService.cancel(principal.user(), callId, reason);
    }

    @Operation(summary = "The room for a call",
            description = """
                    For the two participants only, once it is paid for. Opens five minutes
                    before the start and closes five minutes after the end - early enough
                    not to lock anybody out, late enough that a slight overrun does not cut off.

                    Returns `404` while no media provider is wired in, rather than a null
                    the client would render as an empty player.
                    """)
    @ApiResponse(responseCode = "200", description = "The room URL")
    @ApiResponse(responseCode = "402", description = "Not paid for yet",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Too early, too late, or called off",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/calls/{callId}/room")
    public ResponseEntity<Map<String, String>> room(@AuthenticationPrincipal AuthUser principal,
                                                    @PathVariable UUID callId) {
        return ResponseEntity.ok(Map.of("roomUrl", callService.roomUrl(principal.user(), callId)));
    }
}
