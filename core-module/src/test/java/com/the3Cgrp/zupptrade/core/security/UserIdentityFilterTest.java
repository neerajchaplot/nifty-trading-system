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

    private final JwtService jwt = new JwtService(props());
    private final UserContext ctx = new UserContext();
    private final UserIdentityFilter filter = new UserIdentityFilter(jwt, ctx);

    private static JwtProperties props() {
        JwtProperties p = new JwtProperties();
        p.setSigningKey("0123456789abcdef0123456789abcdef"); // 32 bytes
        return p;
    }

    /** A chain that snapshots the context *while it is set* (before the filter's finally clears it). */
    private FilterChain capturing(AtomicReference<AuthenticatedUser> seen) {
        return (req, res) -> seen.set(ctx.current().orElse(null));
    }

    @Test
    void bearerJwt_populatesContext_thenClearsAfter() throws Exception {
        UUID pid = UUID.randomUUID();
        String token = jwt.issue(new AuthenticatedUser(pid, "LIVE", true, "UPSTOX")).accessToken();
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Authorization")).thenReturn("Bearer " + token);
        AtomicReference<AuthenticatedUser> seen = new AtomicReference<>();

        filter.doFilterInternal(req, mock(HttpServletResponse.class), capturing(seen));

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().profileId()).isEqualTo(pid);
        assertThat(seen.get().admin()).isTrue();
        assertThat(ctx.isAuthenticated()).isFalse(); // cleared in finally
    }

    @Test
    void xUserIdHeader_populatesContext_whenNoBearer() throws Exception {
        UUID pid = UUID.randomUUID();
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Authorization")).thenReturn(null);
        when(req.getHeader(TradingConstants.USER_ID_HEADER)).thenReturn(pid.toString());
        AtomicReference<AuthenticatedUser> seen = new AtomicReference<>();

        filter.doFilterInternal(req, mock(HttpServletResponse.class), capturing(seen));

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().profileId()).isEqualTo(pid);
        assertThat(seen.get().accountMode()).isNull(); // forwarded id carries only the id
    }

    @Test
    void invalidBearer_isAnonymous() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Authorization")).thenReturn("Bearer not-a-jwt");
        AtomicReference<AuthenticatedUser> seen = new AtomicReference<>();

        filter.doFilterInternal(req, mock(HttpServletResponse.class), capturing(seen));

        assertThat(seen.get()).isNull();
    }

    @Test
    void noHeaders_isAnonymous() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        AtomicReference<AuthenticatedUser> seen = new AtomicReference<>();

        filter.doFilterInternal(req, mock(HttpServletResponse.class), capturing(seen));

        assertThat(seen.get()).isNull();
    }
}
