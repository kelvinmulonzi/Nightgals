package com.nightgals.profile;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The cities a profile may say it is in.
 *
 * <p>A closed list rather than a text box, because city is not only shown on a
 * profile - it is filtered on and counted. Free text meant "douala", "Douala ",
 * "DOUALA" and "Duala" were four different places to the browse filters and four
 * rows in the city counts, splitting one city's members across entries that each
 * looked half empty.
 *
 * <p>Douala and Yaoundé lead because they are where most people are; the rest
 * follow roughly by size. The order here is the order the picker shows, so this
 * list is the single source of truth for both ends - the client reads it from
 * {@code GET /api/v1/cities} rather than keeping its own copy to drift.
 *
 * <p>Matching is forgiving even though storage is strict: accents and case are
 * stripped before comparison, so a client sending "yaounde" stores "Yaoundé".
 * That is the one place a typo can still be rescued rather than rejected.
 */
public final class CameroonCity {

    private static final List<String> CANONICAL = List.of(
            "Douala",
            "Yaoundé",
            "Bafoussam",
            "Bamenda",
            "Garoua",
            "Maroua",
            "Ngaoundéré",
            "Bertoua",
            "Buea",
            "Limbe",
            "Kribi",
            "Kumba",
            "Edéa",
            "Ebolowa",
            "Nkongsamba",
            "Dschang",
            "Foumban",
            "Mbouda",
            "Tiko",
            "Sangmélima");

    /** Comparison key -> canonical spelling. Built once. */
    private static final Map<String, String> BY_KEY = buildIndex();

    private CameroonCity() {
    }

    /** In picker order, biggest first. */
    public static List<String> all() {
        return CANONICAL;
    }

    /**
     * The canonical spelling of whatever was sent, if it is one of ours.
     *
     * <p>Empty for anything unrecognised - including a city in another country,
     * which is a real answer rather than an error, and the caller decides what to
     * do about it.
     */
    public static Optional<String> canonical(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_KEY.get(key(input)));
    }

    private static Map<String, String> buildIndex() {
        Map<String, String> index = new LinkedHashMap<>();
        for (String city : CANONICAL) {
            index.put(key(city), city);
        }
        return Map.copyOf(index);
    }

    /**
     * Accents off, case flattened, spaces collapsed. NFD splits "é" into "e" plus
     * a combining mark, and the mark is then stripped by category - which is why
     * this works for every accent on the list rather than the ones we remembered.
     */
    private static String key(String value) {
        String stripped = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return stripped.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
