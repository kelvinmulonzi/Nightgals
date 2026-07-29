package com.nightgals.user;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds Reddit-style handles: adjective + noun + digits, e.g. {@code VelvetFalcon482}.
 *
 * <p>The word lists are deliberately neutral - nightlife and texture words, nothing
 * that hints at gender, appearance or availability. A handle is meant to reveal
 * nothing about the person holding it.
 *
 * <p>Roughly 64 x 64 x 900 = 3.6M combinations, so collisions are rare and the
 * caller only needs a few retries.
 */
@Component
public class UsernameGenerator {

    private static final List<String> ADJECTIVES = List.of(
            "Velvet", "Midnight", "Golden", "Electric", "Silent", "Crimson", "Neon", "Amber",
            "Cosmic", "Silver", "Wild", "Lunar", "Copper", "Swift", "Emerald", "Urban",
            "Sapphire", "Restless", "Hidden", "Radiant", "Wandering", "Stellar", "Quiet", "Bright",
            "Bold", "Curious", "Gentle", "Clever", "Vivid", "Breezy", "Sunlit", "Dusky",
            "Marble", "Indigo", "Coral", "Ivory", "Scarlet", "Cobalt", "Jade", "Onyx",
            "Rustic", "Nomad", "Drifting", "Roaming", "Northern", "Coastal", "Highland", "Riverside",
            "Mellow", "Lively", "Playful", "Steady", "Nimble", "Graceful", "Fearless", "Easy",
            "Sonic", "Rhythmic", "Melodic", "Vintage", "Modern", "Classic", "Smooth", "Crisp");

    private static final List<String> NOUNS = List.of(
            "Falcon", "Comet", "Harbour", "Lantern", "Compass", "Ember", "Willow", "Cedar",
            "Summit", "Meadow", "Canyon", "Delta", "Prairie", "Horizon", "Lagoon", "Reef",
            "Otter", "Heron", "Ibis", "Panther", "Jackal", "Gazelle", "Cobra", "Osprey",
            "Sparrow", "Raven", "Swallow", "Kestrel", "Lynx", "Bison", "Tiger", "Zebra",
            "Rhythm", "Chorus", "Anthem", "Ballad", "Echo", "Cadence", "Tempo", "Melody",
            "Quartz", "Basalt", "Granite", "Opal", "Topaz", "Garnet", "Pearl", "Flint",
            "Voyage", "Journey", "Passage", "Trail", "Avenue", "Boulevard", "Terrace", "Plaza",
            "Beacon", "Signal", "Prism", "Cipher", "Vertex", "Orbit", "Nova", "Zenith");

    /**
     * Handles that must never be auto-generated or claimed, because holding one
     * would let somebody pass themselves off as the platform.
     */
    private static final Set<String> RESERVED = Set.of(
            "admin", "administrator", "root", "superadmin", "moderator", "mod", "staff",
            "support", "help", "helpdesk", "nightgals", "official", "team", "security",
            "system", "verify", "verified", "verification", "kyc", "billing", "payments",
            "me", "you", "null", "undefined", "anonymous", "deleted", "api", "www");

    private final SecureRandom random = new SecureRandom();

    /** One candidate. The caller checks it is free and retries if not. */
    public String generate() {
        return ADJECTIVES.get(random.nextInt(ADJECTIVES.size()))
                + NOUNS.get(random.nextInt(NOUNS.size()))
                + (100 + random.nextInt(900));
    }

    public static boolean isReserved(String username) {
        String lower = username.toLowerCase(Locale.ROOT);
        return RESERVED.contains(lower)
                // Block anything dressed up as a staff account.
                || lower.startsWith("nightgals")
                || lower.startsWith("admin")
                || lower.startsWith("mod_")
                || lower.startsWith("official");
    }
}
