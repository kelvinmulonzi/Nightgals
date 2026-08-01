package com.nightgals.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;

/**
 * Amounts are stored as integers throughout, so no rounding ever happens in
 * floating point. This is the one place that knows how many of those integers
 * make one unit of a given currency.
 *
 * <p>It exists because that answer is not always 100. XAF - the platform's
 * currency - has no subdivision at all: 15000 XAF is fifteen thousand francs,
 * not a hundred and fifty. Dividing by 100 the way a euro or a shilling would
 * be divided understates every price on the site by two orders of magnitude.
 */
public final class Money {

    /**
     * ISO 4217 currencies with an exponent of zero.
     *
     * <p>The CFA francs are the ones that matter here; the rest are included so
     * that pointing the platform at another market does not quietly reintroduce
     * the same bug.
     */
    private static final Set<String> ZERO_DECIMAL = Set.of(
            "XAF", "XOF", "XPF", "BIF", "CLP", "DJF", "GNF", "ISK",
            "JPY", "KMF", "KRW", "PYG", "RWF", "UGX", "UYI", "VND", "VUV");

    private Money() {
    }

    public static boolean isZeroDecimal(String currency) {
        return currency != null && ZERO_DECIMAL.contains(currency.toUpperCase(Locale.ROOT));
    }

    /** How many minor units make one major unit: 1 for XAF, 100 for KES. */
    public static int minorUnits(String currency) {
        return isZeroDecimal(currency) ? 1 : 100;
    }

    public static BigDecimal toMajor(long minor, String currency) {
        return BigDecimal.valueOf(minor)
                .divide(BigDecimal.valueOf(minorUnits(currency)), scale(currency), RoundingMode.HALF_UP);
    }

    /**
     * The amount as a bare number, for an API field a client will parse and
     * format itself: {@code "15000"} for XAF, {@code "150.00"} for KES.
     *
     * <p>Deliberately ungrouped. Thousands separators are a presentation choice
     * that depends on the reader's locale, and a client that has to strip them
     * back out before parsing is a client waiting to get it wrong.
     */
    public static String plain(long minor, String currency) {
        return toMajor(minor, currency).toPlainString();
    }

    /** For prose - an email, a log line, an error message. */
    public static String withCurrency(long minor, String currency) {
        return currency + " " + grouped(minor, currency);
    }

    /** Grouped for human reading: {@code "15,000"}. */
    public static String grouped(long minor, String currency) {
        return String.format(Locale.UK, "%,." + scale(currency) + "f", toMajor(minor, currency));
    }

    private static int scale(String currency) {
        return isZeroDecimal(currency) ? 0 : 2;
    }
}
