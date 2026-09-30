package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.discovery.FeedService;
import com.nightgals.discovery.dto.MemberCardResponse;
import com.nightgals.profile.Gender;
import com.nightgals.profile.ProfileService;
import com.nightgals.profile.dto.ProfileRequest;
import com.nightgals.user.AccountType;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import com.nightgals.user.VerificationStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Discover deals a different hand to every visit.
 *
 * <p>It used to be newest-first inside each package rank, so the same few
 * creators owned the top of the page permanently and everybody further down was
 * effectively unlisted. Now the order inside a rank is a hash of the creator and
 * a seed the caller supplies.
 *
 * <p>Two properties matter and pull against each other: the same seed must give
 * the same order, or paging would repeat and skip cards; different seeds must
 * give different orders, or nothing has changed.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "nightgals.creator-packages.enabled=false",
        "nightgals.monetization.free-trial=P21D",
})
@Transactional
class FeedShuffleTest {

    @Autowired AuthService authService;
    @Autowired ProfileService profileService;
    @Autowired FeedService feedService;
    @Autowired UserRepository userRepository;
    @PersistenceContext EntityManager entityManager;

    @Test
    @DisplayName("The same seed always deals the same order")
    void sameSeedIsStable() {
        creators(12);

        assertThat(idsWithSeed("abc123")).isEqualTo(idsWithSeed("abc123"));
    }

    @Test
    @DisplayName("A different seed puts somebody else on top")
    void differentSeedsDeal2Differently() {
        creators(12);

        // Across a handful of seeds at least one must lead with a different
        // creator. Asserting on one pair would fail by luck once in twelve.
        String first = idsWithSeed("seed-1").getFirst().toString();
        boolean anyDifferent = List.of("seed-2", "seed-3", "seed-4", "seed-5", "seed-6").stream()
                .anyMatch(s -> !idsWithSeed(s).getFirst().toString().equals(first));

        assertThat(anyDifferent)
                .as("no seed changed who is first - the feed is not being shuffled")
                .isTrue();
    }

    @Test
    @DisplayName("One seed's pages fit together: no card repeated, none missed")
    void pagingUnderOneSeedIsCoherent() {
        List<UUID> everyone = creators(9);

        List<UUID> page1 = idsWithSeed("paging", 0, 4);
        List<UUID> page2 = idsWithSeed("paging", 1, 4);
        List<UUID> page3 = idsWithSeed("paging", 2, 4);

        List<UUID> seen = java.util.stream.Stream.of(page1, page2, page3)
                .flatMap(List::stream).toList();

        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen).containsExactlyInAnyOrderElementsOf(everyone);
    }

    @Test
    @DisplayName("No seed at all still returns everybody, in some order")
    void noSeedStillWorks() {
        List<UUID> everyone = creators(5);

        assertThat(feed(null, 0, 50)).containsExactlyInAnyOrderElementsOf(everyone);
    }

    // ------------------------------------------------------------- helpers

    private List<UUID> idsWithSeed(String seed) {
        return feed(seed, 0, 50);
    }

    private List<UUID> idsWithSeed(String seed, int page, int size) {
        return feed(seed, page, size);
    }

    private List<UUID> feed(String seed, int page, int size) {
        // The feed is native SQL, which Hibernate does not auto-flush for.
        entityManager.flush();
        return feedService.feed(null, null, null, null, null, null, null, null, null,
                        seed, PageRequest.of(page, size)).content().stream()
                .map(MemberCardResponse::userId)
                .toList();
    }

    private List<UUID> creators(int howMany) {
        return java.util.stream.IntStream.range(0, howMany)
                .mapToObj(i -> creator().getId())
                .toList();
    }

    private User creator() {
        String email = "shuffle-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", AccountType.CREATOR, null), null);
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        profileService.createOrUpdate(user, new ProfileRequest(
                null, "Weekend only", LocalDate.of(1996, 5, 5),
                Gender.FEMALE, "Douala", "Cameroon", null, null));
        User managed = userRepository.findById(user.getId()).orElseThrow();
        managed.setVerificationStatus(VerificationStatus.APPROVED);
        return userRepository.saveAndFlush(managed);
    }
}
