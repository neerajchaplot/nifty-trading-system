package com.the3Cgrp.zupptrade.agentUser.auth;

import com.the3Cgrp.zupptrade.agentUser.domain.UserProfileEntity;
import com.the3Cgrp.zupptrade.agentUser.service.UserProfileService;
import com.the3Cgrp.zupptrade.core.security.JwtService;
import com.the3Cgrp.zupptrade.core.security.TokenPair;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private final GoogleOidcClient google = mock(GoogleOidcClient.class);
    private final UpstoxAuthClient upstox = mock(UpstoxAuthClient.class);
    private final UserProfileService profiles = mock(UserProfileService.class);
    private final ApiTokenWriter tokenWriter = mock(ApiTokenWriter.class);
    private final JwtService jwt = mock(JwtService.class);

    private final AuthService service = new AuthService(google, upstox, profiles, tokenWriter, jwt);

    private final UUID pid = UUID.randomUUID();
    private final TokenPair pair = new TokenPair("a", "r", 1800);

    private UserProfileEntity profile(String mode, String provider) {
        UserProfileEntity e = new UserProfileEntity() {
            @Override public UUID getId() { return pid; }
        };
        e.setAccountMode(mode);
        e.setAuthProvider(provider);
        return e;
    }

    @Test
    void googleLogin_createsSimulationUser_noTokenStored() {
        when(google.exchangeAndVerify("code"))
                .thenReturn(new GoogleOidcClient.GoogleIdentity("sub-1", "a@b.com", "Ann"));
        when(profiles.findOrCreateForProvider("GOOGLE", "sub-1", "a@b.com", "Ann", "SIMULATION"))
                .thenReturn(profile("SIMULATION", "GOOGLE"));
        when(jwt.issue(any())).thenReturn(pair);

        TokenPair result = service.loginWithGoogle("code");

        assertThat(result).isEqualTo(pair);
        verify(profiles).findOrCreateForProvider("GOOGLE", "sub-1", "a@b.com", "Ann", "SIMULATION");
        verifyNoInteractions(tokenWriter); // simulation users have no broker token
    }

    @Test
    void upstoxLogin_createsLiveUser_storesToken() {
        when(upstox.exchange("code"))
                .thenReturn(new UpstoxAuthClient.UpstoxIdentity("UP123", "u@b.com", "tok-xyz"));
        when(profiles.findOrCreateForProvider(eq("UPSTOX"), eq("UP123"), any(), any(), eq("LIVE")))
                .thenReturn(profile("LIVE", "UPSTOX"));
        when(jwt.issue(any())).thenReturn(pair);

        TokenPair result = service.loginWithUpstox("code");

        assertThat(result).isEqualTo(pair);
        verify(tokenWriter).storeUpstoxToken(pid, "tok-xyz");
    }

    @Test
    void refresh_reissuesForSubject() {
        when(jwt.parseRefreshSubject("refresh")).thenReturn(pid);
        when(profiles.requireById(pid)).thenReturn(profile("LIVE", "UPSTOX"));
        when(jwt.issue(any())).thenReturn(pair);

        assertThat(service.refresh("refresh")).isEqualTo(pair);
        verify(profiles).requireById(pid);
    }
}
