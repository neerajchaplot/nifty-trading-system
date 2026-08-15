package com.the3Cgrp.zupptrade.core.security;

import com.the3Cgrp.zupptrade.core.upstox.crypto.TokenEncryptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BrokerTokenResolverTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<TokenEncryptionService> encProvider = mock(ObjectProvider.class);
    private final TokenEncryptionService enc = mock(TokenEncryptionService.class);

    private final BrokerTokenResolver resolver = new BrokerTokenResolver(jdbc, encProvider);

    private final UUID liveProfile = UUID.randomUUID();
    private final UUID adminProfile = UUID.randomUUID();

    @Test
    void liveUser_usesOwnToken() {
        when(encProvider.getIfAvailable()).thenReturn(enc);
        when(jdbc.queryForObject(contains("api_tokens"), eq(String.class), eq(liveProfile))).thenReturn("ENC");
        when(enc.decrypt("ENC")).thenReturn("live-token");

        String token = resolver.resolveUpstoxToken(new AuthenticatedUser(liveProfile, "LIVE", false, "UPSTOX"));

        assertThat(token).isEqualTo("live-token");
    }

    @Test
    void simulationUser_usesAdminToken() {
        when(jdbc.queryForObject(contains("is_admin"), eq(UUID.class))).thenReturn(adminProfile);
        when(encProvider.getIfAvailable()).thenReturn(enc);
        when(jdbc.queryForObject(contains("api_tokens"), eq(String.class), eq(adminProfile))).thenReturn("ADMIN_ENC");
        when(enc.decrypt("ADMIN_ENC")).thenReturn("admin-token");

        String token = resolver.resolveUpstoxToken(new AuthenticatedUser(UUID.randomUUID(), "SIMULATION", false, "GOOGLE"));

        assertThat(token).isEqualTo("admin-token");
    }

    @Test
    void resolveTokenForProfile_readsThatProfilesToken() {
        when(encProvider.getIfAvailable()).thenReturn(enc);
        when(jdbc.queryForObject(contains("api_tokens"), eq(String.class), eq(liveProfile))).thenReturn("ENC");
        when(enc.decrypt("ENC")).thenReturn("owner-token");

        assertThat(resolver.resolveTokenForProfile(liveProfile)).isEqualTo("owner-token");
    }

    @Test
    void missingToken_blocksWith503() {
        when(jdbc.queryForObject(contains("api_tokens"), eq(String.class), any()))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThatThrownBy(() -> resolver.resolveTokenForProfile(liveProfile))
                .isInstanceOf(BrokerUnavailableException.class)
                .hasMessageContaining("not connected");
    }
}
