package com.nightgals.calls;

import com.nightgals.billing.BillingService;
import com.nightgals.billing.dto.CheckoutResponse;
import com.nightgals.calls.dto.CallRateRequest;
import com.nightgals.calls.dto.CallRateResponse;
import com.nightgals.calls.dto.CallResponse;
import com.nightgals.calls.dto.BookCallRequest;
import com.nightgals.common.ApiException;
import com.nightgals.common.Money;
import com.nightgals.common.PageResponse;
import com.nightgals.config.CallProperties;
import com.nightgals.config.MonetizationProperties;
import com.nightgals.mail.EmailService;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Private 1-to-1 video calls.
 *
 * <p>A creator publishes rates - a price per length, from the lengths the
 * platform allows. A viewer picks one and a time, pays, and both of them get a
 * room when it starts.
 *
 * <p><b>No media server.</b> {@code roomUrl} points at whatever provider is
 * plugged in. Everything here is the half that has to be right whichever
 * provider that turns out to be: who may join, what it costs, when it happens,
 * and that a creator is never double-booked.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallService {

    private final CallRateRepository rateRepository;
    private final VideoCallRepository callRepository;
    private final UserRepository userRepository;
    private final BillingService billingService;
    private final EmailService emailService;
    private final CallProperties properties;
    private final MonetizationProperties monetization;

    // ---------------------------------------------------------------- rates

    /** What this creator charges. Public - it is the price list. */
    @Transactional(readOnly = true)
    public List<CallRateResponse> ratesOf(UUID creatorId) {
        return rateRepository.findByCreatorIdAndActiveTrueOrderByDurationMinutesAsc(creatorId).stream()
                .map(CallRateResponse::of)
                .toList();
    }

    /** The lengths a creator is allowed to price, whether or not she has. */
    public List<Integer> allowedDurations() {
        return properties.allowedDurations();
    }

    /**
     * Sets or clears the price for one length.
     *
     * <p>A null price withdraws that length rather than deleting the row: a
     * booking already made against it still has to be able to explain itself.
     */
    @Transactional
    public CallRateResponse setRate(User creator, CallRateRequest request) {
        requireCallsOn();
        if (!properties.allowedDurations().contains(request.durationMinutes())) {
            throw ApiException.badRequest("unsupported_duration",
                    "Calls can be " + properties.allowedDurations() + " minutes long.");
        }

        CallRate rate = rateRepository
                .findByCreatorIdAndDurationMinutes(creator.getId(), request.durationMinutes())
                .orElseGet(() -> CallRate.builder()
                        .creator(creator)
                        .durationMinutes(request.durationMinutes())
                        .currency(monetization.currency())
                        .build());

        if (request.priceMinor() == null) {
            rate.setActive(false);
        } else {
            rate.setPriceMinor(requireSanePrice(request.priceMinor()));
            rate.setCurrency(monetization.currency());
            rate.setActive(true);
        }

        return CallRateResponse.of(rateRepository.save(rate));
    }

    // ---------------------------------------------------------------- booking

    /**
     * Books a call and starts the payment for it.
     *
     * <p>The slot is held from the moment it is booked, before the money lands.
     * The alternative - hold nothing until paid - lets two people pay for the
     * same slot and leaves the creator to disappoint one of them.
     */
    @Transactional
    public CheckoutResponse book(User viewer, UUID creatorId, BookCallRequest request) {
        requireCallsOn();

        User creator = userRepository.findById(creatorId)
                .orElseThrow(() -> ApiException.notFound("Creator"));
        if (creator.getId().equals(viewer.getId())) {
            throw ApiException.badRequest("self_booking", "You cannot book a call with yourself");
        }
        if (!creator.isApproved()) {
            throw ApiException.notFound("Creator");
        }

        CallRate rate = rateRepository
                .findByCreatorIdAndDurationMinutes(creatorId, request.durationMinutes())
                .filter(CallRate::isActive)
                .orElseThrow(() -> ApiException.badRequest("no_such_rate",
                        "This creator does not offer " + request.durationMinutes() + "-minute calls."));

        requireBookableTime(request.scheduledFor());
        requireSlotFree(creatorId, request.scheduledFor(), request.durationMinutes());

        VideoCall call = callRepository.save(VideoCall.builder()
                .creator(creator)
                .viewer(viewer)
                // Copied, not referenced: a price list that changes later must not
                // rewrite what was agreed.
                .durationMinutes(rate.getDurationMinutes())
                .priceMinor(rate.getPriceMinor())
                .currency(rate.getCurrency())
                .scheduledFor(request.scheduledFor())
                .status(CallStatus.PENDING_PAYMENT)
                .build());

        // Billing flips the booking to CONFIRMED when the money lands, whether that
        // is inside this call or a webhook tomorrow.
        CheckoutResponse checkout = billingService.payForCall(viewer, call);
        if (call.getStatus() == CallStatus.CONFIRMED) {
            announce(call);
        }
        return checkout;
    }

    /** Tells the creator a paid call is in her diary. */
    @Transactional
    public void announce(VideoCall call) {
        emailService.sendCallBooked(
                call.getCreator().getEmail(),
                call.getCreator().getUsername(),
                call.getViewer().getUsername(),
                call.getDurationMinutes(),
                Money.withCurrency(call.getPriceMinor(), call.getCurrency()),
                call.getScheduledFor().toString());

        log.info("Call {} confirmed: {} with {} for {} min",
                call.getId(), call.getViewer().getId(), call.getCreator().getId(),
                call.getDurationMinutes());
    }

    @Transactional
    public CallResponse cancel(User actor, UUID callId, String reason) {
        VideoCall call = requireParticipant(actor, callId);
        if (call.getStatus() == CallStatus.COMPLETED || call.getStatus() == CallStatus.LIVE) {
            throw ApiException.conflict("already_started", "This call has already happened");
        }
        call.setStatus(actor.getId().equals(call.getCreator().getId())
                ? CallStatus.DECLINED : CallStatus.CANCELLED);
        call.setCancelledReason(reason);
        return CallResponse.of(call, actor.getId());
    }

    // ---------------------------------------------------------------- the room

    /**
     * The room, for one of the two participants, once it is paid for and close
     * enough to the start to be worth opening.
     */
    @Transactional(readOnly = true)
    public String roomUrl(User actor, UUID callId) {
        VideoCall call = requireParticipant(actor, callId);

        if (!call.isPaid()) {
            throw ApiException.paymentRequired("This call has not been paid for yet");
        }
        if (call.getStatus() == CallStatus.CANCELLED || call.getStatus() == CallStatus.DECLINED) {
            throw ApiException.conflict("call_cancelled", "This call was called off");
        }
        // Five minutes of grace either side, so nobody is locked out for being
        // early and a slightly overrunning call does not cut off.
        Instant now = Instant.now();
        if (now.isBefore(call.getScheduledFor().minusSeconds(300))) {
            throw ApiException.conflict("too_early",
                    "The room opens five minutes before the call starts.");
        }
        if (now.isAfter(call.endsAt().plusSeconds(300))) {
            throw ApiException.conflict("too_late", "This call has finished");
        }
        if (call.getRoomUrl() == null) {
            // No media provider is wired in, so there is nothing to hand back.
            // Deliberately explicit rather than returning null and letting the
            // client render an empty player.
            throw ApiException.notFound("Call room");
        }
        return call.getRoomUrl();
    }

    // ---------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public PageResponse<CallResponse> myCalls(User user, Pageable pageable) {
        return PageResponse.from(
                callRepository.findForUser(user.getId(), pageable),
                call -> CallResponse.of(call, user.getId()));
    }

    // ---------------------------------------------------------------- internals

    private VideoCall requireParticipant(User actor, UUID callId) {
        VideoCall call = callRepository.findById(callId)
                .orElseThrow(() -> ApiException.notFound("Call"));
        if (!call.involves(actor.getId()) && !actor.isStaff()) {
            // 404 rather than 403: a stranger should not learn the id exists.
            throw ApiException.notFound("Call");
        }
        return call;
    }

    private void requireBookableTime(Instant when) {
        Instant now = Instant.now();
        if (when.isBefore(now.plus(properties.minNotice()))) {
            throw ApiException.badRequest("too_soon",
                    "Book at least " + properties.minNotice().toMinutes() + " minutes ahead.");
        }
        if (when.isAfter(now.plus(properties.maxLeadTime()))) {
            throw ApiException.badRequest("too_far_ahead",
                    "Calls can be booked up to " + properties.maxLeadTime().toDays() + " days ahead.");
        }
    }

    /**
     * Refuses a slot that overlaps one already held.
     *
     * <p>Overlap, not equality. A 60-minute call at 20:00 and a 15-minute one at
     * 20:30 collide even though they start at different times, and a uniqueness
     * check on the start instant alone would accept both.
     */
    private void requireSlotFree(UUID creatorId, Instant start, int durationMinutes) {
        Instant end = start.plusSeconds(durationMinutes * 60L);
        boolean clash = callRepository.findPotentialClashes(creatorId, end).stream()
                .anyMatch(existing -> existing.endsAt().isAfter(start));
        if (clash) {
            throw ApiException.conflict("slot_taken",
                    "She is already booked then. Try another time.");
        }
    }

    private long requireSanePrice(long priceMinor) {
        if (priceMinor < properties.floor()) {
            throw ApiException.badRequest("price_too_low",
                    "The lowest you can charge for a call is "
                    + Money.withCurrency(properties.floor(), monetization.currency()) + ".");
        }
        if (priceMinor > properties.ceiling()) {
            throw ApiException.badRequest("price_too_high",
                    "The most you can charge for a call is "
                    + Money.withCurrency(properties.ceiling(), monetization.currency()) + ".");
        }
        return priceMinor;
    }

    private void requireCallsOn() {
        if (!properties.enabled()) {
            throw ApiException.conflict("calls_disabled",
                    "Private calls are not available on this deployment");
        }
    }
}
