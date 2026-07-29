package com.nightgals.user;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The authenticated principal. Wraps the User entity so controllers can read
 * the id, role and verification status straight off the security context.
 */
public record AuthUser(User user) implements UserDetails {

    public UUID id() {
        return user.getId();
    }

    /**
     * The caller, or null when the request is anonymous.
     *
     * <p>Spring injects a null principal on permitAll endpoints, so every handler
     * that both anonymous and signed-in callers can reach must go through this.
     */
    public static User userOrNull(AuthUser principal) {
        return principal == null ? null : principal.user();
    }

    public String username() {
        return user.getUsername();
    }

    public Role role() {
        return user.getRole();
    }

    public VerificationStatus verificationStatus() {
        return user.getVerificationStatus();
    }

    public boolean isApproved() {
        return user.isApproved();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return user.getEmail();
    }

    @Override
    public boolean isAccountNonLocked() {
        return user.getStatus() != UserStatus.SUSPENDED;
    }

    @Override
    public boolean isEnabled() {
        return user.getStatus() == UserStatus.ACTIVE;
    }
}
