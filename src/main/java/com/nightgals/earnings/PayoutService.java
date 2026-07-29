package com.nightgals.earnings;

import com.nightgals.common.ApiException;
import com.nightgals.common.PageResponse;
import com.nightgals.config.EarningsProperties;
import com.nightgals.config.MonetizationProperties;
import com.nightgals.earnings.dto.EarningsSummaryResponse;
import com.nightgals.earnings.dto.PayoutAccountRequest;
import com.nightgals.earnings.dto.PayoutResponse;
import com.nightgals.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Creators asking to be paid, and an administrator paying them.
 *
 * <p>The balance is never a stored number. Requesting a payout moves the
 * creator's AVAILABLE entries to RESERVED and stamps them with the payout id;
 * that, plus the partial unique index allowing only one open payout per creator,
 * is what stops the same money being paid twice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutService {

    private final EarningRepository earningRepository;
    private final PayoutRepository payoutRepository;
    private final PayoutAccountRepository accountRepository;
    private final EarningsProperties earningsProperties;
    private final MonetizationProperties monetizationProperties;

    // ------------------------------------------------------------ creator side

    @Transactional(readOnly = true)
    public EarningsSummaryResponse summary(User creator) {
        UUID id = creator.getId();
        return new EarningsSummaryResponse(
                monetizationProperties.currency(),
                earningRepository.sumNetByStatus(id, EarningStatus.AVAILABLE),
                earningRepository.sumNetByStatus(id, EarningStatus.PENDING),
                earningRepository.sumNetByStatus(id, EarningStatus.RESERVED),
                earningRepository.sumNetByStatus(id, EarningStatus.PAID),
                earningRepository.sumLifetimeNet(id),
                earningsProperties.minimumPayoutMinor(),
                earningRepository.sumNetByStatus(id, EarningStatus.AVAILABLE)
                        >= earningsProperties.minimumPayoutMinor(),
                payoutRepository.findOpenForCreator(id).isPresent(),
                accountRepository.findByUserId(id).isPresent());
    }

    @Transactional(readOnly = true)
    public PageResponse<com.nightgals.earnings.dto.EarningResponse> ledger(UUID creatorId, Pageable pageable) {
        return PageResponse.from(
                earningRepository.findByCreatorIdOrderByCreatedAtDesc(creatorId, pageable),
                com.nightgals.earnings.dto.EarningResponse::of);
    }

    @Transactional
    public PayoutAccount saveAccount(User creator, PayoutAccountRequest request) {
        if (request.method() == PayoutMethod.BANK_TRANSFER
                && (request.bankName() == null || request.bankName().isBlank())) {
            throw ApiException.badRequest("bank_name_required",
                    "A bank name is required for bank transfers");
        }
        // Changing the destination mid-payout would send money somewhere the
        // admin never checked.
        if (payoutRepository.findOpenForCreator(creator.getId()).isPresent()) {
            throw ApiException.conflict("payout_in_progress",
                    "You cannot change your payout account while a payout is being processed");
        }

        PayoutAccount account = accountRepository.findByUserId(creator.getId())
                .orElseGet(() -> PayoutAccount.builder().user(creator).build());
        account.setMethod(request.method());
        account.setDestination(request.destination().replaceAll("\\s+", ""));
        account.setAccountName(request.accountName().trim());
        account.setBankName(request.bankName());
        return accountRepository.save(account);
    }

    @Transactional(readOnly = true)
    public PayoutAccount getAccount(UUID creatorId) {
        return accountRepository.findByUserId(creatorId)
                .orElseThrow(() -> ApiException.notFound("Payout account"));
    }

    /** Requests payment of the creator's whole available balance. */
    @Transactional
    public PayoutResponse requestPayout(User creator) {
        PayoutAccount account = accountRepository.findByUserId(creator.getId())
                .orElseThrow(() -> ApiException.badRequest("no_payout_account",
                        "Add a payout account before requesting a payout"));

        if (payoutRepository.findOpenForCreator(creator.getId()).isPresent()) {
            throw ApiException.conflict("payout_in_progress",
                    "You already have a payout being processed");
        }

        long available = earningRepository.sumNetByStatus(creator.getId(), EarningStatus.AVAILABLE);
        if (available < earningsProperties.minimumPayoutMinor()) {
            throw ApiException.badRequest("below_minimum",
                    "Minimum payout is " + money(earningsProperties.minimumPayoutMinor())
                            + " " + monetizationProperties.currency()
                            + "; your available balance is " + money(available));
        }

        Payout payout;
        try {
            payout = payoutRepository.saveAndFlush(Payout.builder()
                    .creator(creator)
                    .amountMinor(available)
                    .currency(monetizationProperties.currency())
                    .status(PayoutStatus.REQUESTED)
                    .method(account.getMethod())
                    .destination(account.getDestination())
                    .accountName(account.getAccountName())
                    .build());
        } catch (DataIntegrityViolationException e) {
            // The partial unique index caught a concurrent second request.
            throw ApiException.conflict("payout_in_progress",
                    "You already have a payout being processed");
        }

        // Reserve exactly the entries that make up this amount.
        List<Earning> entries = earningRepository.findByCreatorIdAndStatus(
                creator.getId(), EarningStatus.AVAILABLE);
        entries.forEach(e -> {
            e.setStatus(EarningStatus.RESERVED);
            e.setPayoutId(payout.getId());
        });

        log.info("Creator {} requested payout {} of {} minor units",
                creator.getId(), payout.getId(), available);
        return PayoutResponse.of(payout, false);
    }

    @Transactional(readOnly = true)
    public PageResponse<PayoutResponse> creatorPayouts(UUID creatorId, Pageable pageable) {
        return PageResponse.from(
                payoutRepository.findByCreatorIdOrderByCreatedAtDesc(creatorId, pageable),
                p -> PayoutResponse.of(p, false));
    }

    // ------------------------------------------------------------ admin side

    @Transactional(readOnly = true)
    public PageResponse<PayoutResponse> queue(Pageable pageable) {
        return PageResponse.from(
                payoutRepository.findQueue(List.of(PayoutStatus.REQUESTED, PayoutStatus.APPROVED), pageable),
                // Admins need the real destination in order to send the money.
                p -> PayoutResponse.of(p, true));
    }

    @Transactional(readOnly = true)
    public long queueCount() {
        return payoutRepository.countByStatus(PayoutStatus.REQUESTED);
    }

    @Transactional
    public PayoutResponse approve(UUID payoutId, User admin) {
        Payout payout = requirePayout(payoutId);
        if (payout.getStatus() != PayoutStatus.REQUESTED) {
            throw ApiException.conflict("not_requested", "Payout is " + payout.getStatus());
        }
        payout.setStatus(PayoutStatus.APPROVED);
        payout.setProcessedBy(admin);
        return PayoutResponse.of(payout, true);
    }

    /** Records that money has actually been sent, and closes out the entries. */
    @Transactional
    public PayoutResponse markPaid(UUID payoutId, String reference, User admin) {
        Payout payout = requirePayout(payoutId);
        if (!payout.isOpen()) {
            throw ApiException.conflict("not_open", "Payout is " + payout.getStatus());
        }
        if (reference == null || reference.isBlank()) {
            throw ApiException.badRequest("reference_required",
                    "Record the transaction reference so the payment can be traced");
        }

        payout.setStatus(PayoutStatus.PAID);
        payout.setReference(reference.trim());
        payout.setProcessedAt(Instant.now());
        payout.setProcessedBy(admin);

        earningRepository.findByPayoutId(payout.getId())
                .forEach(e -> e.setStatus(EarningStatus.PAID));

        log.info("Payout {} marked PAID by {} (ref {})", payoutId, admin.getEmail(), reference);
        return PayoutResponse.of(payout, true);
    }

    /** Refuses a payout and returns the reserved entries to the creator's balance. */
    @Transactional
    public PayoutResponse reject(UUID payoutId, String reason, User admin) {
        Payout payout = requirePayout(payoutId);
        if (!payout.isOpen()) {
            throw ApiException.conflict("not_open", "Payout is " + payout.getStatus());
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("reason_required", "A rejection reason is required");
        }

        payout.setStatus(PayoutStatus.REJECTED);
        payout.setRejectionReason(reason.trim());
        payout.setProcessedAt(Instant.now());
        payout.setProcessedBy(admin);

        earningRepository.findByPayoutId(payout.getId()).forEach(e -> {
            e.setStatus(EarningStatus.AVAILABLE);
            e.setPayoutId(null);
        });

        log.info("Payout {} rejected by {}", payoutId, admin.getEmail());
        return PayoutResponse.of(payout, true);
    }

    @Transactional(readOnly = true)
    public java.util.Map<String, Object> platformSummary() {
        return java.util.Map.of(
                "currency", monetizationProperties.currency(),
                "commissionPercent", earningsProperties.commissionPercent(),
                "platformCommissionMinor", earningRepository.sumPlatformCommission(),
                "creatorsOwedMinor", earningRepository.sumNetAcrossPlatform(EarningStatus.AVAILABLE)
                        + earningRepository.sumNetAcrossPlatform(EarningStatus.RESERVED),
                "creatorsPendingMinor", earningRepository.sumNetAcrossPlatform(EarningStatus.PENDING),
                "totalPaidOutMinor", payoutRepository.sumPaidOut(),
                "payoutsAwaiting", payoutRepository.countByStatus(PayoutStatus.REQUESTED));
    }

    private Payout requirePayout(UUID payoutId) {
        return payoutRepository.findById(payoutId)
                .orElseThrow(() -> ApiException.notFound("Payout"));
    }

    private String money(long minor) {
        return String.format("%.2f", minor / 100.0);
    }
}
