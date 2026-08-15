package com.the3Cgrp.zupptrade.core.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Policy tests for {@link OwnershipGuard}: anonymous → 401, admin → bypass,
 * owner → allow, other user → 403.
 */
class OwnershipGuardTest {

    private final UserContext userContext = new UserContext();
    private final OwnershipGuard guard = new OwnershipGuard(userContext);

    private final UUID alice = UUID.randomUUID();
    private final UUID bob   = UUID.randomUUID();

    private void loginAs(UUID profileId, boolean admin) {
        userContext.set(new AuthenticatedUser(profileId, "LIVE", admin, "UPSTOX"));
    }

    // ── requireProfileId ───────────────────────────────────────────────────────

    @Test
    void requireProfileId_anonymous_throws401() {
        assertThatThrownBy(guard::requireProfileId)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void requireProfileId_loggedIn_returnsCallerId() {
        loginAs(alice, false);
        assertThat(guard.requireProfileId()).isEqualTo(alice);
    }

    // ── scopeProfileId (list scoping) ──────────────────────────────────────────

    @Test
    void scopeProfileId_normalUser_returnsOwnId() {
        loginAs(alice, false);
        assertThat(guard.scopeProfileId()).isEqualTo(alice);
    }

    @Test
    void scopeProfileId_admin_returnsNull_meaningUnscoped() {
        loginAs(alice, true);
        assertThat(guard.scopeProfileId()).isNull();
    }

    @Test
    void scopeProfileId_anonymous_throws401() {
        assertThatThrownBy(guard::scopeProfileId)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    // ── requireOwner (by-id) ───────────────────────────────────────────────────

    @Test
    void requireOwner_owner_passes() {
        loginAs(alice, false);
        assertThatCode(() -> guard.requireOwner(alice)).doesNotThrowAnyException();
    }

    @Test
    void requireOwner_otherUser_throws403() {
        loginAs(alice, false);
        assertThatThrownBy(() -> guard.requireOwner(bob))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void requireOwner_admin_seesAnyOwner() {
        loginAs(alice, true);
        assertThatCode(() -> guard.requireOwner(bob)).doesNotThrowAnyException();
    }

    @Test
    void requireOwner_nullOwner_visibleToAdminOnly() {
        loginAs(alice, true);
        assertThatCode(() -> guard.requireOwner(null)).doesNotThrowAnyException();

        loginAs(bob, false);
        assertThatThrownBy(() -> guard.requireOwner(null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void requireOwner_anonymous_throws401_notFound() {
        assertThatThrownBy(() -> guard.requireOwner(alice))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void isAdmin_reflectsFlag() {
        assertThat(guard.isAdmin()).isFalse();   // anonymous
        loginAs(alice, false);
        assertThat(guard.isAdmin()).isFalse();
        loginAs(alice, true);
        assertThat(guard.isAdmin()).isTrue();
    }
}
