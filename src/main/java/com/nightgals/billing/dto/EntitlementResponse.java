package com.nightgals.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "What the caller currently has access to")
public record EntitlementResponse(
        @Schema(description = "True while a subscription is running") boolean subscribed,
        @Schema(description = "Plan code of the running subscription") String planCode,
        @Schema(description = "When the subscription lapses") Instant subscriptionExpiresAt,
        @Schema(description = "Members unlocked individually") List<UnlockedMember> unlockedMembers) {

    @Schema(description = "A member this viewer has paid to see")
    public record UnlockedMember(
            UUID userId,
            String username,
            @Schema(description = "Null means the unlock never expires") Instant expiresAt) {
    }
}
