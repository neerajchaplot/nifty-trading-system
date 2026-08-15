package com.the3Cgrp.zupptrade.agentUser.auth;

import com.the3Cgrp.zupptrade.agentUser.domain.UserProfileEntity;
import com.the3Cgrp.zupptrade.agentUser.service.UserProfileService;
import com.the3Cgrp.zupptrade.core.security.AuthenticatedUser;
import com.the3Cgrp.zupptrade.core.security.JwtService;
import com.the3Cgrp.zupptrade.core.security.TokenPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Turns a provider login (Upstox or Google) into an app session (JWT), upserting the user's
 * profile and — for live users — storing their broker token. Google users are simulation-only
 * (no token); Upstox users are live.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final GoogleOidcClient google;
    private final UpstoxAuthClient upstox;
    private final UserProfileService profiles;
    private final ApiTokenWriter tokenWriter;
    private final JwtService jwt;

    public AuthService(GoogleOidcClient google, UpstoxAuthClient upstox,
                       UserProfileService profiles, ApiTokenWriter tokenWriter, JwtService jwt) {
        this.google = google;
        this.upstox = upstox;
        this.profiles = profiles;
        this.tokenWriter = tokenWriter;
        this.jwt = jwt;
    }

    public TokenPair loginWithGoogle(String code) {
        GoogleOidcClient.GoogleIdentity id = google.exchangeAndVerify(code);
        UserProfileEntity profile = profiles.findOrCreateForProvider(
                "GOOGLE", id.sub(), id.email(), id.name(), "SIMULATION");
        log.info("auth.login.google profileId={} email={}", profile.getId(), id.email());
        return jwt.issue(toAuthUser(profile));
    }

    public TokenPair loginWithUpstox(String code) {
        UpstoxAuthClient.UpstoxIdentity id = upstox.exchange(code);
        UserProfileEntity profile = profiles.findOrCreateForProvider(
                "UPSTOX", id.userId(), id.email(), id.userId(), "LIVE");
        // Store the live user's broker token (encrypted) so orders/reads can act as them.
        tokenWriter.storeUpstoxToken(profile.getId(), id.accessToken());
        log.info("auth.login.upstox profileId={} upstoxUserId={}", profile.getId(), id.userId());
        return jwt.issue(toAuthUser(profile));
    }

    public TokenPair refresh(String refreshToken) {
        UUID profileId = jwt.parseRefreshSubject(refreshToken);
        UserProfileEntity profile = profiles.requireById(profileId);
        return jwt.issue(toAuthUser(profile));
    }

    private AuthenticatedUser toAuthUser(UserProfileEntity p) {
        return new AuthenticatedUser(p.getId(), p.getAccountMode(), p.isAdmin(), p.getAuthProvider());
    }
}
