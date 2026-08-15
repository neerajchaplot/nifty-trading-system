package com.the3Cgrp.zupptrade.core.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Per-user read isolation (multi-user Phase 5). One primitive reused by every agent so scoping
 * behaves identically everywhere.
 *
 * <p>Policy (decisions locked with the user):
 * <ul>
 *   <li><b>Anonymous → 401.</b> User-facing reads require a validated identity (JWT or forwarded
 *       {@code X-User-Id}). There is no legacy all-access fallback.</li>
 *   <li><b>Admin → bypass.</b> {@code is_admin} sees every user's rows (system oversight).</li>
 *   <li><b>Everyone else → own rows only.</b> A by-id read of another user's row is 403; list reads
 *       are filtered to the caller's {@code user_profiles.id}.</li>
 * </ul>
 *
 * <p>Throws {@link ResponseStatusException}, which Spring renders as an RFC 9457 ProblemDetail —
 * no per-module handler needed.
 */
public class OwnershipGuard {

    private final UserContext userContext;

    public OwnershipGuard(UserContext userContext) {
        this.userContext = userContext;
    }

    /** True when an authenticated caller is an admin (sees all). Never throws. */
    public boolean isAdmin() {
        return userContext.current().map(AuthenticatedUser::admin).orElse(false);
    }

    /**
     * The acting user's profile id, or 401 if the request is anonymous.
     * Use when an identity is mandatory regardless of scoping (e.g. resolving "me").
     */
    public UUID requireProfileId() {
        return userContext.current()
                .map(AuthenticatedUser::profileId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Authentication required"));
    }

    /**
     * Scope value for a list query: {@code null} for admins (return everyone — caller must then omit
     * the WHERE clause), otherwise the caller's profile id (filter to it). 401 if anonymous.
     *
     * <pre>{@code
     *   UUID scope = guard.scopeProfileId();     // 401 if not logged in
     *   if (scope == null) return repo.findAll();          // admin
     *   return repo.findForUser(scope);                    // normal user
     * }</pre>
     */
    public UUID scopeProfileId() {
        if (isAdmin()) {
            return null;
        }
        return requireProfileId();
    }

    /**
     * Guards a by-id read/write: passes for the owner or an admin; 401 if anonymous; 403 otherwise.
     * A {@code null} owner (unattributed legacy row) is only visible to an admin.
     */
    public void requireOwner(UUID rowOwnerId) {
        UUID caller = requireProfileId();     // 401 first if anonymous
        if (isAdmin()) {
            return;
        }
        if (rowOwnerId == null || !rowOwnerId.equals(caller)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You do not have access to this resource");
        }
    }
}
