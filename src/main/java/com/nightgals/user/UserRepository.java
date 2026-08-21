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
