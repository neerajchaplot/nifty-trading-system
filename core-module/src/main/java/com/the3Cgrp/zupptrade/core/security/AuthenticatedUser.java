package com.the3Cgrp.zupptrade.core.security;

import java.util.UUID;

/**
 * The identity carried in a validated access token — the internal user, not a provider account.
 * {@code profileId} is the {@code user_profiles.id} everything else in the system scopes by.
 *
 * <p>Shared across all agents (moved here from agent-user in Phase 3 so validators and the minter
 * use one definition).
 */
public record AuthenticatedUser(
        UUID profileId,
        String accountMode,   // SIMULATION | LIVE
        boolean admin,
        String authProvider   // UPSTOX | GOOGLE
) {}
