package com.nightgals.calls.dto;

import com.nightgals.calls.CallStatus;
import com.nightgals.calls.VideoCall;
import com.nightgals.common.Money;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "One booked private call, from the caller's side of it")
public record CallResponse(
        UUID id,
        @Schema(description = "True when the caller is the creator being called")
        boolean iAmTheCreator,
        @Schema(description = "The other person's handle") String withUsername,
        int durationMinutes,
        long priceMinor,
        String priceDisplay,
        String currency,
        Instant scheduledFor,
        Instant endsAt,
        CallStatus status,
        @Schema(description = "True once it is paid for and still going ahead") boolean confirmed,
        String cancelledReason,
        Instant createdAt) {

    public static CallResponse of(VideoCall call, UUID callerId) {
        boolean mine = call.getCreator().getId().equals(callerId);
        return new CallResponse(
                call.getId(),
                mine,
                mine ? call.getViewer().getUsername() : call.getCreator().getUsername(),
                call.getDurationMinutes(),
                call.getPriceMinor(),
                Money.plain(call.getPriceMinor(), call.getCurrency()),
                call.getCurrency(),
                call.getScheduledFor(),
                call.endsAt(),
                call.getStatus(),
                call.getStatus() == CallStatus.CONFIRMED || call.getStatus() == CallStatus.LIVE,
                call.getCancelledReason(),
                call.getCreatedAt());
    }
}
