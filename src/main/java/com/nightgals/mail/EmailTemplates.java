package com.nightgals.mail;

/**
 * The HTML our emails are made of.
 *
 * <p>Hand-written rather than templated, because email clients are not browsers:
 * tables, inline styles and no external stylesheet is the only thing that
 * survives Outlook, Gmail's clipper and a dark-mode phone. A template engine
 * would not change any of that, only add a dependency.
 *
 * <p>Every message is also given a plain-text alternative by the caller, so the
 * code in a one-time-code email is readable even where HTML is stripped.
 */
final class EmailTemplates {

    // Kept in step with the frontend's theme.js "dark luxe" palette.
    private static final String BG = "#08070A";
    private static final String SURFACE = "#16151D";
    private static final String LINE = "#272531";
    private static final String GOLD = "#D9B46A";
    private static final String GOLD_BRIGHT = "#F0D49A";
    private static final String TEXT = "#F4F1EA";
    private static final String MUTED = "#9A948A";

    private EmailTemplates() {
    }

    /** Shell every message sits in: dark card, gold wordmark, small print. */
    static String page(String brand, String preheader, String body, String supportEmail) {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <meta name="color-scheme" content="dark light">
                </head>
                <body style="margin:0;padding:0;background:%1$s;">
                  <!-- Shown in the inbox preview line, never on the page itself. -->
                  <div style="display:none;max-height:0;overflow:hidden;opacity:0;">%2$s</div>
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0"
                         style="background:%1$s;padding:32px 16px;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                    <tr>
                      <td align="center">
                        <table role="presentation" width="100%%" cellpadding="0" cellspacing="0"
                               style="max-width:520px;background:%3$s;border:1px solid %4$s;border-radius:20px;overflow:hidden;">
                          <tr>
                            <td style="padding:28px 32px 8px 32px;">
                              <div style="font-size:20px;font-weight:700;letter-spacing:3px;color:%5$s;text-transform:uppercase;">%6$s</div>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:8px 32px 32px 32px;color:%7$s;font-size:15px;line-height:1.65;">
                              %8$s
                            </td>
                          </tr>
                        </table>
                        <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:520px;">
                          <tr>
                            <td style="padding:20px 32px;color:%9$s;font-size:12px;line-height:1.6;text-align:center;">
                              You are receiving this because someone used this address on %6$s.<br>
                              Questions? <a href="mailto:%10$s" style="color:%5$s;text-decoration:none;">%10$s</a>
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """.formatted(BG, escape(preheader), SURFACE, LINE, GOLD, escape(brand),
                TEXT, body, MUTED, escape(supportEmail));
    }

    static String heading(String text) {
        return "<h1 style=\"margin:16px 0 8px 0;font-size:22px;font-weight:600;color:" + TEXT + ";\">"
                + escape(text) + "</h1>";
    }

    static String paragraph(String text) {
        return "<p style=\"margin:0 0 16px 0;color:" + MUTED + ";\">" + escape(text) + "</p>";
    }

    /** The code itself: big, spaced, and selectable as one token. */
    static String code(String value) {
        return """
                <div style="margin:24px 0;padding:20px;background:rgba(217,180,106,0.10);
                            border:1px solid rgba(217,180,106,0.35);border-radius:14px;text-align:center;">
                  <div style="font-size:34px;font-weight:700;letter-spacing:10px;color:%s;
                              font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;">%s</div>
                </div>
                """.formatted(GOLD_BRIGHT, escape(value));
    }

    /** Label/value rows, for receipts. */
    static String rows(String... labelsAndValues) {
        StringBuilder sb = new StringBuilder("""
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0"
                       style="margin:20px 0;border-top:1px solid """ + LINE + ";\">");
        for (int i = 0; i + 1 < labelsAndValues.length; i += 2) {
            sb.append("""
                    <tr>
                      <td style="padding:11px 0;border-bottom:1px solid %s;color:%s;font-size:13px;">%s</td>
                      <td style="padding:11px 0;border-bottom:1px solid %s;color:%s;font-size:13px;
                                 font-weight:600;text-align:right;">%s</td>
                    </tr>
                    """.formatted(LINE, MUTED, escape(labelsAndValues[i]),
                    LINE, TEXT, escape(labelsAndValues[i + 1])));
        }
        return sb.append("</table>").toString();
    }

    static String button(String label, String href) {
        return """
                <div style="margin:24px 0;">
                  <a href="%s" style="display:inline-block;padding:13px 26px;background:%s;color:#0B0A0E;
                     font-weight:700;font-size:14px;border-radius:12px;text-decoration:none;">%s</a>
                </div>
                """.formatted(escape(href), GOLD, escape(label));
    }

    static String note(String text) {
        return "<p style=\"margin:16px 0 0 0;padding-top:16px;border-top:1px solid " + LINE
                + ";color:#6B665E;font-size:12.5px;line-height:1.6;\">" + escape(text) + "</p>";
    }

    /**
     * Everything interpolated into these templates is user- or config-supplied,
     * so it is escaped rather than trusted. A username is the obvious carrier:
     * it reaches an inbox, and an inbox renders HTML.
     */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
