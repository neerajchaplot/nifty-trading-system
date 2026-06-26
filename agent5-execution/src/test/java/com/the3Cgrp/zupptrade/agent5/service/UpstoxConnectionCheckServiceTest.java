package com.the3Cgrp.zupptrade.agent5.service;

import com.the3Cgrp.zupptrade.agent5.dto.UpstoxStatusResponse;
import com.the3Cgrp.zupptrade.core.upstox.auth.UpstoxTokenHolder;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxProfileClient;
import com.the3Cgrp.zupptrade.core.upstox.config.UpstoxProperties;
import com.the3Cgrp.zupptrade.core.upstox.model.UpstoxUserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for UpstoxConnectionCheckService.
 * All external calls (UpstoxProfileClient) are mocked — no network needed.
 */
@ExtendWith(MockitoExtension.class)
class UpstoxConnectionCheckServiceTest {

    @Mock private UpstoxProfileClient  profileClient;
    @Mock private UpstoxTokenHolder    tokenHolder;
    @Mock private UpstoxProperties     props;

    private UpstoxConnectionCheckService service;

    private static final String GATEWAY         = "https://api-hft.upstox.com";
    private static final String SANDBOX_GATEWAY = "https://api-sandbox.upstox.com";
    private static final String VALID_TOKEN     = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.stub";
    private static final UpstoxUserProfile PROFILE =
            new UpstoxUserProfile("USR001", "Test User", "test@example.com",
                    "individual", "ZERODHA", List.of("NSE"), List.of("D"), List.of("LIMIT", "MARKET"));

    @BeforeEach
    void setUp() {
        // lenient: isTokenLoaded tests don't call check(), so this stub is unused there
        lenient().when(props.getOrderBaseUrl()).thenReturn(GATEWAY);
        service = new UpstoxConnectionCheckService(profileClient, tokenHolder, props);
    }

    // ── isTokenLoaded ─────────────────────────────────────────────────────────

    @Test
    void isTokenLoaded_withValidToken_returnsTrue() {
        when(tokenHolder.getToken()).thenReturn(VALID_TOKEN);
        assertThat(service.isTokenLoaded()).isTrue();
    }

    @Test
    void isTokenLoaded_withNullToken_returnsFalse() {
        when(tokenHolder.getToken()).thenReturn(null);
        assertThat(service.isTokenLoaded()).isFalse();
    }

    @Test
    void isTokenLoaded_withBlankToken_returnsFalse() {
        when(tokenHolder.getToken()).thenReturn("   ");
        assertThat(service.isTokenLoaded()).isFalse();
    }

    // ── check — happy path ────────────────────────────────────────────────────

    @Test
    void check_tokenValidNoSandbox_returnsConnectedWithoutSandbox() {
        when(tokenHolder.getToken()).thenReturn(VALID_TOKEN);
        when(props.getOrderAccessToken()).thenReturn(null);
        when(profileClient.getProfile()).thenReturn(PROFILE);

        UpstoxStatusResponse result = service.check();

        assertThat(result.tokenStatus()).isEqualTo("LOADED");
        assertThat(result.productionApiReachable()).isTrue();
        assertThat(result.userId()).isEqualTo("USR001");
        assertThat(result.userName()).isEqualTo("Test User");
        assertThat(result.sandboxTokenConfigured()).isFalse();
        assertThat(result.orderGateway()).isEqualTo(GATEWAY);
        assertThat(result.errorDetail()).isNull();
        assertThat(result.checkedAt()).isNotNull();
    }

    @Test
    void check_tokenValidWithSandboxToken_reportsSandboxConfigured() {
        when(tokenHolder.getToken()).thenReturn(VALID_TOKEN);
        when(props.getOrderAccessToken()).thenReturn("sandbox-token-xyz");
        when(props.getOrderBaseUrl()).thenReturn(SANDBOX_GATEWAY);
        when(profileClient.getProfile()).thenReturn(PROFILE);

        UpstoxStatusResponse result = service.check();

        assertThat(result.tokenStatus()).isEqualTo("LOADED");
        assertThat(result.sandboxTokenConfigured()).isTrue();
        assertThat(result.orderGateway()).isEqualTo(SANDBOX_GATEWAY);
    }

    // ── check — failure paths ─────────────────────────────────────────────────

    @Test
    void check_tokenAbsent_returnsAbsentWithoutCallingApi() {
        when(tokenHolder.getToken()).thenReturn(null);

        UpstoxStatusResponse result = service.check();

        assertThat(result.tokenStatus()).isEqualTo("ABSENT");
        assertThat(result.productionApiReachable()).isFalse();
        assertThat(result.errorDetail()).isNotBlank();
        verifyNoInteractions(profileClient);
    }

    @Test
    void check_tokenBlank_returnsAbsentWithoutCallingApi() {
        when(tokenHolder.getToken()).thenReturn("");

        UpstoxStatusResponse result = service.check();

        assertThat(result.tokenStatus()).isEqualTo("ABSENT");
        verifyNoInteractions(profileClient);
    }

    @Test
    void check_upstoxReturns401_returnsExpired() {
        when(tokenHolder.getToken()).thenReturn(VALID_TOKEN);
        when(props.getOrderAccessToken()).thenReturn(null);
        when(profileClient.getProfile())
                .thenThrow(HttpClientErrorException.Unauthorized.class);

        UpstoxStatusResponse result = service.check();

        assertThat(result.tokenStatus()).isEqualTo("EXPIRED");
        assertThat(result.productionApiReachable()).isFalse();
        assertThat(result.errorDetail()).containsIgnoringCase("401");
    }

    @Test
    void check_networkException_returnsUnreachable() {
        when(tokenHolder.getToken()).thenReturn(VALID_TOKEN);
        when(props.getOrderAccessToken()).thenReturn(null);
        when(profileClient.getProfile())
                .thenThrow(new RuntimeException("Connection timed out"));

        UpstoxStatusResponse result = service.check();

        assertThat(result.tokenStatus()).isEqualTo("LOADED");   // token was present
        assertThat(result.productionApiReachable()).isFalse();
        assertThat(result.errorDetail()).containsIgnoringCase("timed out");
    }
}
