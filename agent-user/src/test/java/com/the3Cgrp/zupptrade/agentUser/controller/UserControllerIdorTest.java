package com.the3Cgrp.zupptrade.agentUser.controller;

import com.the3Cgrp.zupptrade.agentUser.dto.UpdateUserProfileRequestDto;
import com.the3Cgrp.zupptrade.agentUser.service.UserProfileService;
import com.the3Cgrp.zupptrade.core.security.AuthenticatedUser;
import com.the3Cgrp.zupptrade.core.security.OwnershipGuard;
import com.the3Cgrp.zupptrade.core.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 5 IDOR guard: a user may only touch their OWN profile via /me/profile/{profileId}.
 * A mismatched path id is 403; anonymous is 401; admin may touch any.
 */
class UserControllerIdorTest {

    private final UserProfileService service = mock(UserProfileService.class);
    private final UserContext userContext = new UserContext();
    private final OwnershipGuard guard = new OwnershipGuard(userContext);
    private final UserController controller = new UserController(service, userContext, guard);

    private final UUID alice = UUID.randomUUID();
    private final UUID bob   = UUID.randomUUID();

    private void loginAs(UUID id, boolean admin) {
        userContext.set(new AuthenticatedUser(id, "LIVE", admin, "UPSTOX"));
    }

    @AfterEach
    void clear() { userContext.clear(); }

    // ── updateProfile ───────────────────────────────────────────────────────────

    @Test
    void updateProfile_ownProfile_proceeds() {
        loginAs(alice, false);
        when(service.updateProfile(eq(alice), any())).thenReturn(null);

        assertThatCode(() -> controller.updateProfile(alice, (UpdateUserProfileRequestDto) null))
                .doesNotThrowAnyException();
        verify(service).updateProfile(eq(alice), any());
    }

    @Test
    void updateProfile_anotherUsersProfile_throws403_neverCallsService() {
        loginAs(bob, false);

        assertThatThrownBy(() -> controller.updateProfile(alice, (UpdateUserProfileRequestDto) null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(service, never()).updateProfile(any(), any());
    }

    @Test
    void updateProfile_admin_mayEditAnyProfile() {
        loginAs(bob, true);
        when(service.updateProfile(eq(alice), any())).thenReturn(null);

        assertThatCode(() -> controller.updateProfile(alice, (UpdateUserProfileRequestDto) null))
                .doesNotThrowAnyException();
    }

    @Test
    void updateProfile_anonymous_throws401() {
        assertThatThrownBy(() -> controller.updateProfile(alice, (UpdateUserProfileRequestDto) null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    // ── getAudit ────────────────────────────────────────────────────────────────

    @Test
    void getAudit_anotherUsersProfile_throws403_neverCallsService() {
        loginAs(bob, false);

        assertThatThrownBy(() -> controller.getAudit(alice))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(service, never()).getAudit(any());
    }

    @Test
    void getAudit_ownProfile_proceeds() {
        loginAs(alice, false);
        when(service.getAudit(alice)).thenReturn(List.of());

        assertThatCode(() -> controller.getAudit(alice)).doesNotThrowAnyException();
        verify(service).getAudit(alice);
    }
}
