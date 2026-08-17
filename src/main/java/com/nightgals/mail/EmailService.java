package com.nightgals.mail;

import com.nightgals.common.ApiException;
import com.nightgals.config.NotificationProperties;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Every message the platform sends.
 *
 * <p>Two delivery modes, and the difference matters:
 *
 * <ul>
 *   <li>{@link #sendLoginCode} and {@link #sendVerificationCode} are
 *       <b>synchronous and throw</b>. A sign-in that cannot deliver its code is a
 *       sign-in that cannot complete, so the caller must find out now rather than
 *       leave somebody staring at a box waiting for mail that is not coming.
 *   <li>Everything else is {@code @Async} and swallows failures. A receipt that
 *       does not arrive is annoying; a receipt that rolls back a payment is worse.
 * </ul>
 */
@Slf4j
@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final NotificationProperties properties;
    private final String brand;

    public EmailService(JavaMailSender mailSender, NotificationProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
        // Follows MAIL_FROM_NAME rather than a constant. It was hardcoded to
        // "Nightgals", which put the wrong name in the subject, the wordmark and
        // the footer of every message the platform sent from noctyvera.com — a
        // sign-in code branded as another product is exactly what a phishing
        // filter is looking for, and what a recipient is told to distrust.
        this.brand = properties.fromName();
    }

    // ------------------------------------------------------------ one-time codes

    /** The code that completes a sign-in. Blocking, and throws if it cannot be sent. */
    public void sendLoginCode(String to, String username, String code, Duration validFor) {
        String body = EmailTemplates.heading("Your sign-in code")
                + EmailTemplates.paragraph("Hi " + username + ", use this code to finish signing in.")
                + EmailTemplates.code(code)
                + EmailTemplates.paragraph("It expires in " + minutes(validFor)
                        + " and can only be used once.")
                + EmailTemplates.note("""
                        If you did not just try to sign in, someone else has your password. \
                        Change it as soon as you can - and do not share this code with anyone, \
                        including anybody claiming to be from support.""");

        sendOrThrow(to, "Your " + brand + " sign-in code: " + code,
                body, "Your sign-in code is " + code + ". It expires in " + minutes(validFor) + ".",
                "Your sign-in code is " + code);
    }

    /** Confirms the address on a new account. Blocking, and throws if it cannot be sent. */
    public void sendVerificationCode(String to, String username, String code, Duration validFor) {
        String body = EmailTemplates.heading("Confirm your email")
                + EmailTemplates.paragraph("Welcome, " + username
                        + ". Enter this code to confirm this is your address.")
                + EmailTemplates.code(code)
                + EmailTemplates.paragraph("It expires in " + minutes(validFor) + ".")
                + EmailTemplates.note("""
                        We ask for this once. A confirmed address is how you get back into your \
                        account if you forget your password, and where your sign-in codes go.""");

        sendOrThrow(to, "Confirm your email: " + code,
                body, "Your confirmation code is " + code + ". It expires in " + minutes(validFor) + ".",
                "Your confirmation code is " + code);
    }

    // ------------------------------------------------------------ notifications

    /** Sent once the address is confirmed. */
    @Async
    public void sendWelcome(String to, String username, boolean creator) {
        String body = EmailTemplates.heading("You're in")
                + EmailTemplates.paragraph("Your handle is " + username
                        + ". It is what everyone else on " + brand
                        + " sees - your real name never appears anywhere on the site.")
                + (creator
                        ? EmailTemplates.paragraph("""
                                Two things stand between you and your first payout: verify your \
                                identity, then pick the package that matches what you post.""")
                        : EmailTemplates.paragraph("""
                                Browse whoever you like. When you find someone worth seeing more of, \
                                one payment unlocks everything she has posted."""))
                + EmailTemplates.button(creator ? "Set up your profile" : "Start browsing",
                        properties.appBaseUrl() + (creator ? "/studio" : "/discover"));

        sendQuietly(to, "Welcome to " + brand, body,
                "Welcome to " + brand + ". Your handle is " + username + ".");
    }

    /** Sent to a creator when her package payment settles. */
    @Async
    public void sendPackageReceipt(String to, String username, String packageLabel,
                                   String amount, String expiresOn, int photoLimit, int videoLimit) {
        String body = EmailTemplates.heading(packageLabel + " is active")
                + EmailTemplates.paragraph("Thanks " + username + " - your package is live. "
                        + "Here is what it covers.")
                + EmailTemplates.rows(
                        "Package", packageLabel,
                        "Paid", amount,
                        "Photos", photoLimit == 0 ? "Not included" : String.valueOf(photoLimit),
                        "Videos", videoLimit == 0 ? "Not included" : String.valueOf(videoLimit),
                        "Renews on", expiresOn)
                + EmailTemplates.button("Go to your studio", properties.appBaseUrl() + "/studio");

        sendQuietly(to, packageLabel + " is active", body,
                packageLabel + " is active until " + expiresOn + ".");
    }

    /** Sent to a viewer when an unlock settles, so they know access is open. */
    @Async
    public void sendUnlockReceipt(String to, String creatorName, String amount, String expiresOn) {
        String body = EmailTemplates.heading("Unlocked: " + creatorName)
                + EmailTemplates.paragraph("Everything " + creatorName
                        + " has posted is now open to you.")
                + EmailTemplates.rows(
                        "Creator", creatorName,
                        "Paid", amount,
                        "Access until", expiresOn)
                + EmailTemplates.button("See her content", properties.appBaseUrl() + "/discover");

        sendQuietly(to, "You unlocked " + creatorName, body,
                "You unlocked " + creatorName + ". Access runs until " + expiresOn + ".");
    }

    /** Sent to a creator when somebody pays for her. */
    @Async
    public void sendNewFanNotice(String to, String creatorName, String amountEarned) {
        String body = EmailTemplates.heading("Someone just unlocked you")
                + EmailTemplates.paragraph("Hi " + creatorName
                        + " - a new viewer paid to see your content. "
                        + amountEarned + " has been added to your pending earnings.")
                + EmailTemplates.button("View earnings", properties.appBaseUrl() + "/studio");

        sendQuietly(to, "You have a new subscriber", body,
                "A new viewer unlocked your profile. " + amountEarned + " added to pending earnings.");
    }

    /** Sent to a viewer when they buy one item. */
    @Async
    public void sendItemReceipt(String to, String creatorName, String what, String amount) {
        String body = EmailTemplates.heading("Unlocked")
                + EmailTemplates.paragraph("You now have " + what + " from " + creatorName + ".")
                + EmailTemplates.rows("Creator", creatorName, "Item", what, "Paid", amount)
                + EmailTemplates.button("Watch it now", properties.appBaseUrl() + "/discover");

        sendQuietly(to, "You unlocked " + what, body,
                "You unlocked " + what + " from " + creatorName + " for " + amount + ".");
    }

    /** Sent to a creator when one of her items sells. */
    @Async
    public void sendItemSoldNotice(String to, String creatorName, String what, String amount) {
        String body = EmailTemplates.heading("Someone bought " + what)
                + EmailTemplates.paragraph("Hi " + creatorName + " - " + amount
                        + " has been added to your pending earnings.")
                + EmailTemplates.button("View earnings", properties.appBaseUrl() + "/studio");

        sendQuietly(to, "You made a sale", body, what + " sold for " + amount + ".");
    }

    /** Sent to a referrer when somebody they invited buys their first package. */
    @Async
    public void sendReferralBonus(String to, String username, String referredName, String amount) {
        String body = EmailTemplates.heading("You earned " + amount)
                + EmailTemplates.paragraph("Hi " + username + " - " + referredName
                        + " just took out their first package, so your referral bonus is in.")
                + EmailTemplates.rows("Invited", referredName, "Bonus", amount)
                + EmailTemplates.paragraph("Credit is spendable on anything here: your own "
                        + "package, or unlocking somebody else's content.")
                + EmailTemplates.button("See your credit", properties.appBaseUrl() + "/referrals");

        sendQuietly(to, "You earned " + amount + " in credit", body,
                referredName + " subscribed. " + amount + " credited to your account.");
    }

    /** Sent to a creator when a private call is booked and paid for. */
    @Async
    public void sendCallBooked(String to, String creatorName, String viewerName,
                               int minutes, String amount, String when) {
        String body = EmailTemplates.heading("A call is booked")
                + EmailTemplates.paragraph("Hi " + creatorName + " - " + viewerName
                        + " booked a private call with you and has already paid.")
                + EmailTemplates.rows(
                        "With", viewerName,
                        "Length", minutes + " minutes",
                        "When", when,
                        "You earn", amount)
                + EmailTemplates.button("See your calls", properties.appBaseUrl() + "/studio/calls");

        sendQuietly(to, "New call booked", body,
                viewerName + " booked a " + minutes + "-minute call.");
    }

    /** Sent to a follower shortly before a creator's scheduled broadcast. */
    @Async
    public void sendLiveReminder(String to, String followerName, String creatorName,
                                 String title, String when) {
        String body = EmailTemplates.heading(creatorName + " is going live")
                + EmailTemplates.paragraph("Hi " + followerName + " - " + title
                        + " starts at " + when + ".")
                + EmailTemplates.button("Go to the room", properties.appBaseUrl() + "/live");

        sendQuietly(to, creatorName + " is live soon", body,
                creatorName + " goes live at " + when + ".");
    }

    /**
     * Sent the moment identity documents reach the review queue.
     *
     * <p>Review is not instant and the screen that says so is one the user
     * navigates away from. Without this, handing over a passport is followed by
     * silence, which reads as the upload having failed - and the usual response
     * to that is to submit again.
     */
    @Async
    public void sendVerificationReceived(String to, String username) {
        String body = EmailTemplates.heading("Your documents are with us")
                + EmailTemplates.paragraph("Hi " + username
                        + ", we have your identity documents and a reviewer is checking them.")
                + EmailTemplates.paragraph("Most checks are done within a day. We will email you"
                        + " as soon as there is a decision - there is nothing else for you to do,"
                        + " and no need to send them again.")
                + EmailTemplates.button("Check the status", properties.appBaseUrl() + "/onboarding/status");

        sendQuietly(to, "We are reviewing your documents", body,
                "Your identity documents are under review.");
    }

    /** Sent when an administrator decides on an identity submission. */
    @Async
    public void sendVerificationDecision(String to, String username, boolean approved, String reason) {
        String body = approved
                ? EmailTemplates.heading("You're verified")
                        + EmailTemplates.paragraph("Hi " + username
                                + ", your identity check passed. Pick a package and start posting.")
                        + EmailTemplates.button("Choose a package", properties.appBaseUrl() + "/studio/packages")
                : EmailTemplates.heading("We could not verify your documents")
                        + EmailTemplates.paragraph("Hi " + username + ", the check did not pass: "
                                + (reason == null || reason.isBlank() ? "the documents were not usable." : reason))
                        + EmailTemplates.paragraph("You can submit again whenever you are ready.")
                        + EmailTemplates.button("Try again", properties.appBaseUrl() + "/onboarding/verify");

        sendQuietly(to, approved ? "You're verified" : "Verification unsuccessful", body,
                approved ? "Your identity check passed." : "Your identity check did not pass.");
    }

    // ------------------------------------------------------------ plumbing

    private void sendOrThrow(String to, String subject, String html, String text, String logLine) {
        if (!properties.enabled()) {
            // Development without SMTP: the code goes to the log so the flow is
            // still completable. Deliberately loud - this must never be how a
            // production deployment is running.
            log.warn("EMAIL DISABLED - would have sent to {}: {}", to, logLine);
            return;
        }
        try {
            deliver(to, subject, html, text);
        } catch (MailException | jakarta.mail.MessagingException | UnsupportedEncodingException e) {
            log.error("Could not send '{}' to {}: {}", subject, to, e.getMessage());
            throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "email_send_failed",
                    "We could not send the code to your email. Try again in a moment.");
        }
    }

    private void sendQuietly(String to, String subject, String html, String text) {
        if (!properties.enabled()) {
            log.debug("Email disabled - skipping '{}' to {}", subject, to);
            return;
        }
        try {
            deliver(to, subject, html, text);
        } catch (Exception e) {
            // Nothing downstream depends on this arriving, and this runs on a
            // separate thread from the transaction that triggered it.
            log.warn("Could not send '{}' to {}: {}", subject, to, e.getMessage());
        }
    }

    private void deliver(String to, String subject, String html, String text)
            throws jakarta.mail.MessagingException, UnsupportedEncodingException {
        MimeMessage message = mailSender.createMimeMessage();
        // true, UTF-8: multipart, so a client that will not render HTML still
        // gets the plain-text alternative rather than a blank message.
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        helper.setFrom(properties.from(), properties.fromName());
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(text, EmailTemplates.page(brand, text, html, properties.supportEmail()));
        mailSender.send(message);
        log.debug("Sent '{}' to {}", subject, to);
    }

    private static String minutes(Duration duration) {
        long m = Math.max(1, duration.toMinutes());
        return m + (m == 1 ? " minute" : " minutes");
    }
}
