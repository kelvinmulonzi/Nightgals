package com.nightgals.referral;

import com.nightgals.common.PageResponse;
import com.nightgals.referral.dto.CreditEntryResponse;
import com.nightgals.referral.dto.ReferralSummaryResponse;
import com.nightgals.user.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "11. Referrals", description = """
        Invite codes and the credit they earn.

        Every account gets a code at registration and it never changes. When somebody
        who signed up with it buys their **first** package, the referrer is credited.

        The bonus lands on first purchase, not on signup - paying for registrations
        would make this a bot farm.

        Credit is spendable on anything: a package, a video, a broadcast, a call. It is
        applied before the payment provider is involved, so a purchase it covers
        entirely settles with no payment at all.
        """)
@RestController
@RequestMapping("/api/v1/me/referrals")
@RequiredArgsConstructor
public class ReferralController {

    private final ReferralService referralService;
    private final CreditService creditService;

    @Operation(summary = "My code, my link, and how it is doing")
    @ApiResponse(responseCode = "200", description = "Code, share link, counts and credit balance")
    @GetMapping
    public ReferralSummaryResponse summary(@AuthenticationPrincipal AuthUser principal) {
        return referralService.summaryFor(principal.user());
    }

    @Operation(summary = "Every movement of my credit",
            description = "Positive is credit earned, negative is credit spent. Append-only.")
    @ApiResponse(responseCode = "200", description = "Ledger entries, newest first")
    @GetMapping("/credit")
    public PageResponse<CreditEntryResponse> credit(@AuthenticationPrincipal AuthUser principal,
                                                    @PageableDefault(size = 20) Pageable pageable) {
        return creditService.history(principal.id(), pageable);
    }
}
