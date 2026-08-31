package com.nightgals.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    @Query("SELECT u FROM User u WHERE LOWER(u.email) = LOWER(:email)")
    Optional<User> findByEmailIgnoreCase(@Param("email") String email);

    @Query("SELECT COUNT(u) > 0 FROM User u WHERE LOWER(u.email) = LOWER(:email)")
    boolean existsByEmailIgnoreCase(@Param("email") String email);

    /** Google's subject claim, which outlives any address change on that account. */
    Optional<User> findByGoogleSubject(String googleSubject);

    @Query("SELECT u FROM User u WHERE LOWER(u.username) = LOWER(:username)")
    Optional<User> findByUsernameIgnoreCase(@Param("username") String username);

    @Query("SELECT COUNT(u) > 0 FROM User u WHERE LOWER(u.username) = LOWER(:username)")
    boolean existsByUsernameIgnoreCase(@Param("username") String username);

    boolean existsByReferralCodeIgnoreCase(String referralCode);

    java.util.Optional<User> findByReferralCodeIgnoreCase(String referralCode);

    long countByReferredById(java.util.UUID referrerId);

    /**
     * The staff console's account list: search by address or handle, optionally
     * narrowed to one status.
     *
     * <p>A null {@code status} means every account, which is what the console
     * opens on. A blank {@code q} means no search - written as a null-or-blank
     * test rather than two query methods, because the alternative is four
     * combinations of the same SQL kept in step by hand.
     */
    @Query("""
            SELECT u FROM User u
            WHERE (:status IS NULL OR u.status = :status)
              AND (:q IS NULL OR :q = ''
                   OR LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(u.username) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    org.springframework.data.domain.Page<User> search(
            @Param("q") String q,
            @Param("status") UserStatus status,
            org.springframework.data.domain.Pageable pageable);

    long countByStatus(UserStatus status);

    /**
     * Who referred this account, loaded rather than navigated.
     *
     * <p>{@code user.getReferredBy()} is a lazy proxy, and the principal on a
     * request was loaded by the auth filter outside any transaction - so touching
     * that proxy later throws LazyInitializationException. Fetching by id sidesteps
     * the whole question.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT u.referredBy FROM User u WHERE u.id = :userId AND u.referredBy IS NOT NULL")
    java.util.Optional<User> findReferrerOf(
            @org.springframework.data.repository.query.Param("userId") java.util.UUID userId);
}
