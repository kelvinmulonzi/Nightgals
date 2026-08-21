package com.nightgals.auth.google;

/**
 * What a verified Google ID token tells us about the person holding it.
 *
 * @param subject       Google's permanent id for the account. Stable across an
 *                      address change, which the email is not.
 * @param email         Lower-cased by the verifier, so callers never have to.
 * @param emailVerified Google's own verdict on the address. False means the
 *                      token proves an account exists, not that its owner reads
 *                      that inbox - which is the only thing we wanted it for.
 * @param name          Display name, when the profile scope was granted.
 */
public record GoogleIdentity(String subject, String email, boolean emailVerified, String name) {
}
