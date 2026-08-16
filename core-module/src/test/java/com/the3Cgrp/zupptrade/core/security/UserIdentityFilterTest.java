package com.the3Cgrp.zupptrade.core.security;

import com.the3Cgrp.zupptrade.shared.constants.TradingConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserIdentityFilterTest {

    private static final String KEY = "test-internal-key-0123456789";

    private final JwtService jwt = new JwtService(props());
    private final UserContext ctx = new UserContext();
    private final UserIdentityFilter filter = new UserIdentityFilter(jwt, ctx, KEY);

    private static JwtProperties props() {
        JwtProperties p = new JwtProperties();
        p.setSigningKey("0123456789abcdef0123456789abcdef"); // 32 bytes
        return p;
    }

    /** A chain that snapshots the context *while it is set* (before the filter's finally clears it). */
    private FilterChain capturing(AtomicReference<AuthenticatedUser> seen) {
        return (req, res) -> seen.set(ctx.current().orElse(null));
    }

    private AuthenticatedUser run(HttpServletRequest req) throws Exception {
        AtomicReference<AuthenticatedUser> seen = new AtomicReference<>();
        filter.doFilterInternal(req, mock(HttpServletResponse.class), capturing(seen));
        return seen.get();
    }

    @Test
    void bearerJwt_populatesContext_thenClearsAfter() throws Exception {
        UUID pid = UUID.randomUUID();
        String token = jwt.issue(new AuthenticatedUser(pid, "LIVE", true, "UPSTOX")).accessToken();
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Authorization")).thenReturn("Bearer " + token);

        AuthenticatedUser seen = run(req);

        assertThat(seen).isNotNull();
        assertThat(seen.profileId()).isEqualTo(pid);
        assertThat(seen.admin()).isTrue();
        assertThat(ctx.isAuthenticated()).isFalse(); // cleared in finally
    }

    @Test
    void xUserId_withValidApiKey_populatesContext() throws Exception {
        UUID pid = UUID.randomUUID();
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader(TradingConstants.USER_ID_HEADER)).thenReturn(pid.toString());
        when(req.getHeader(TradingConstants.API_KEY_HEADER)).thenReturn(KEY);

        AuthenticatedUser seen = run(req);

        assertThat(seen).isNotNull();
        assertThat(seen.profileId()).isEqualTo(pid);
        assertThat(seen.accountMode()).isNull(); // forwarded id carries only the id
    }

    /** The vulnerability: X-User-Id with NO key must be ignored (no impersonation). */
    @Test
    void xUserId_withoutApiKey_isAnonymous() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader(TradingConstants.USER_ID_HEADER)).thenReturn(UUID.randomUUID().toString());
        // no X-API-Key header

        assertThat(run(req)).isNull();
    }

    @Test
    void xUserId_withWrongApiKey_isAnonymous() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader(TradingConstants.USER_ID_HEADER)).thenReturn(UUID.randomUUID().toString());
        when(req.getHeader(TradingConstants.API_KEY_HEADER)).thenReturn("wrong-key");

        assertThat(run(req)).isNull();
    }

    @Test
    void invalidBearer_isAnonymous() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Authorization")).thenReturn("Bearer not-a-jwt");

        assertThat(run(req)).isNull();
    }

    @Test
    void noHeaders_isAnonymous() throws Exception {
        assertThat(run(mock(HttpServletRequest.class))).isNull();
    }
}
