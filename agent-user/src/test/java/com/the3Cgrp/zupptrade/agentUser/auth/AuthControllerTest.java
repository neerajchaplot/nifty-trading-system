package com.the3Cgrp.zupptrade.agentUser.auth;

import com.the3Cgrp.zupptrade.core.security.TokenPair;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Certifies the mobile-vs-web redirect branching added for native app login.
 * Login encodes the client type into OAuth {@code state}; the callback reads it back and
 * routes mobile logins to the {@code zupptrade://} deep link, web logins to the SPA.
 */
class AuthControllerTest {

    private final AuthService authService = mock(AuthService.class);
    private final UpstoxAuthClient upstox = mock(UpstoxAuthClient.class);
    private final GoogleOidcClient google = mock(GoogleOidcClient.class);
    private final AuthProperties props = new AuthProperties();

    private final AuthController controller = new AuthController(authService, upstox, google, props);

    private final TokenPair pair = new TokenPair("acc", "ref", 1800);

    AuthControllerTest() {
        props.setUiRedirectUri("https://web.example.com/auth/callback");
        props.setMobileRedirectUri("zupptrade://auth/callback");
    }

    @Test
    void googleCallback_mobileState_redirectsToDeepLinkWithTokens() {
        when(authService.loginWithGoogle("code")).thenReturn(pair);

        ResponseEntity<Void> resp = controller.googleCallback("code", "uuid.mobile");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        String loc = resp.getHeaders().getLocation().toString();
        assertThat(loc).startsWith("zupptrade://auth/callback#");
        assertThat(loc).contains("access_token=acc")
                       .contains("refresh_token=ref")
                       .contains("expires_in=1800");
    }

    @Test
    void googleCallback_webState_redirectsToWebUi() {
        when(authService.loginWithGoogle("code")).thenReturn(pair);
        String loc = controller.googleCallback("code", "uuid.web").getHeaders().getLocation().toString();
        assertThat(loc).startsWith("https://web.example.com/auth/callback#");
    }

    @Test
    void googleCallback_nullState_defaultsToWebUi() {
        when(authService.loginWithGoogle("code")).thenReturn(pair);
        String loc = controller.googleCallback("code", null).getHeaders().getLocation().toString();
        assertThat(loc).startsWith("https://web.example.com/auth/callback#");
    }

    @Test
    void upstoxCallback_mobileState_redirectsToDeepLink() {
        when(authService.loginWithUpstox("code")).thenReturn(pair);
        String loc = controller.upstoxCallback("code", "uuid.mobile").getHeaders().getLocation().toString();
        assertThat(loc).startsWith("zupptrade://auth/callback#");
    }

    @Test
    void googleLogin_withMobileClient_encodesMobileIntoState() {
        when(google.buildAuthorizationUrl(anyString())).thenReturn("https://accounts.google.com/o/oauth2/v2/auth");

        controller.googleLogin("mobile");

        ArgumentCaptor<String> state = ArgumentCaptor.forClass(String.class);
        verify(google).buildAuthorizationUrl(state.capture());
        assertThat(state.getValue()).endsWith(".mobile");
    }

    @Test
    void upstoxLogin_withoutClient_encodesWebIntoState() {
        when(upstox.buildAuthorizationUrl(anyString())).thenReturn("https://api.upstox.com/v2/login/authorization/dialog");

        controller.upstoxLogin(null);

        ArgumentCaptor<String> state = ArgumentCaptor.forClass(String.class);
        verify(upstox).buildAuthorizationUrl(state.capture());
        assertThat(state.getValue()).endsWith(".web");
    }
}
