package com.the3Cgrp.zupptrade.core.security;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private JwtService service(String key) {
        JwtProperties p = new JwtProperties();
        p.setSigningKey(key);
        p.setAccessTtlMinutes(30);
        p.setRefreshTtlDays(7);
        return new JwtService(p);
    }

    private static final String KEY = "0123456789abcdef0123456789abcdef"; // 32 bytes

    @Test
    void issueThenParseAccess_roundTripsIdentity() {
        JwtService jwt = service(KEY);
        UUID pid = UUID.randomUUID();
        AuthenticatedUser user = new AuthenticatedUser(pid, "SIMULATION", false, "GOOGLE");

        TokenPair pair = jwt.issue(user);
        AuthenticatedUser parsed = jwt.parseAccess(pair.accessToken());

        assertThat(parsed.profileId()).isEqualTo(pid);
        assertThat(parsed.accountMode()).isEqualTo("SIMULATION");
        assertThat(parsed.admin()).isFalse();
        assertThat(parsed.authProvider()).isEqualTo("GOOGLE");
        assertThat(pair.accessExpiresInSeconds()).isEqualTo(1800);
    }

    @Test
    void refreshToken_parsesAsRefresh_notAsAccess() {
        JwtService jwt = service(KEY);
        UUID pid = UUID.randomUUID();
        TokenPair pair = jwt.issue(new AuthenticatedUser(pid, "LIVE", true, "UPSTOX"));

        assertThat(jwt.parseRefreshSubject(pair.refreshToken())).isEqualTo(pid);
        assertThatThrownBy(() -> jwt.parseAccess(pair.refreshToken())).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> jwt.parseRefreshSubject(pair.accessToken())).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithDifferentKey_isRejected() {
        JwtService a = service(KEY);
        JwtService b = service("ffffffffffffffffffffffffffffffff");
        TokenPair pair = a.issue(new AuthenticatedUser(UUID.randomUUID(), "LIVE", true, "UPSTOX"));

        assertThatThrownBy(() -> b.parseAccess(pair.accessToken())).isInstanceOf(JwtException.class);
    }

    @Test
    void unconfiguredKey_bootsButFailsWhenUsed() {
        JwtProperties p = new JwtProperties();
        p.setSigningKey("too-short");
        JwtService jwt = new JwtService(p); // must NOT throw — agents boot without a key
        assertThatThrownBy(() -> jwt.issue(new AuthenticatedUser(UUID.randomUUID(), "LIVE", true, "UPSTOX")))
                .isInstanceOf(IllegalStateException.class);
    }
}
