package com.the3Cgrp.zupptrade.agentUser.auth;

import com.the3Cgrp.zupptrade.core.security.TokenPair;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * Login/session endpoints. Login redirects the browser to the provider; the provider redirects
 * back to our callback with a code; we exchange it, mint a JWT pair, and hand the browser to the
 * SPA with the tokens in the URL fragment (fragments aren't sent to servers/logs).
 */
@RestController
@RequestMapping("/api/v1/agent-user/auth")
public class AuthController {

    private final AuthService authService;
    private final UpstoxAuthClient upstox;
    private final GoogleOidcClient google;
    private final AuthProperties props;

    public AuthController(AuthService authService, UpstoxAuthClient upstox,
                          GoogleOidcClient google, AuthProperties props) {
        this.authService = authService;
        this.upstox = upstox;
        this.google = google;
        this.props = props;
    }

    // ── Upstox (live) ─────────────────────────────────────────

    @GetMapping("/upstox/login")
    public ResponseEntity<Void> upstoxLogin() {
        return redirect(upstox.buildAuthorizationUrl(newState()));
    }

    @GetMapping("/upstox/callback")
    public ResponseEntity<Void> upstoxCallback(@RequestParam("code") String code) {
        return redirect(uiRedirectWithTokens(authService.loginWithUpstox(code)));
    }

    // ── Google (simulation) ───────────────────────────────────

    @GetMapping("/google/login")
    public ResponseEntity<Void> googleLogin() {
        return redirect(google.buildAuthorizationUrl(newState()));
    }

    @GetMapping("/google/callback")
    public ResponseEntity<Void> googleCallback(@RequestParam("code") String code) {
        return redirect(uiRedirectWithTokens(authService.loginWithGoogle(code)));
    }

    // ── Session ───────────────────────────────────────────────

    @PostMapping("/refresh")
    public ResponseEntity<TokenPair> refresh(@RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request.refreshToken()));
    }

    /** Stateless logout — the client discards its tokens. Kept for a clean UI contract. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ───────────────────────────────────────────────

    private static String newState() {
        return UUID.randomUUID().toString();
    }

    private String uiRedirectWithTokens(TokenPair pair) {
        return UriComponentsBuilder.fromUriString(props.getUiRedirectUri())
                .fragment("access_token=" + pair.accessToken()
                        + "&refresh_token=" + pair.refreshToken()
                        + "&expires_in=" + pair.accessExpiresInSeconds())
                .build().toUriString();
    }

    private static ResponseEntity<Void> redirect(String url) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }

    public record RefreshRequest(@NotBlank String refreshToken) {}
}
