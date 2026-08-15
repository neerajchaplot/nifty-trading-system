package com.the3Cgrp.zupptrade.agentUser.controller;

import com.the3Cgrp.zupptrade.core.security.OwnershipGuard;
import com.the3Cgrp.zupptrade.core.security.UserContext;
import com.the3Cgrp.zupptrade.agentUser.dto.UpdateUserProfileRequestDto;
import com.the3Cgrp.zupptrade.agentUser.dto.UserProfileAuditDto;
import com.the3Cgrp.zupptrade.agentUser.dto.UserProfileResponseDto;
import com.the3Cgrp.zupptrade.agentUser.service.UserProfileService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agent-user")
public class UserController {

    private final UserProfileService userProfileService;
    private final UserContext userContext;
    private final OwnershipGuard guard;

    public UserController(UserProfileService userProfileService, UserContext userContext,
                          OwnershipGuard guard) {
        this.userProfileService = userProfileService;
        this.userContext = userContext;
        this.guard = guard;
    }

    /**
     * Resolves the current Upstox identity to the internal user_profiles UUID.
     * Called by the UI on load. Creates or claims the profile row on first run.
     * Returns the full profile including all risk parameters and Agent 1 weights.
     */
    @GetMapping("/me")
    public ResponseEntity<UserProfileResponseDto> me() {
        // When a valid JWT is present, resolve the authenticated profile; otherwise fall back to
        // the legacy single-user Upstox resolution so the current UI keeps working pre-Phase 7.
        return ResponseEntity.ok(
                userContext.current()
                        .map(u -> userProfileService.getByProfileId(u.profileId()))
                        .orElseGet(userProfileService::findOrCreateForCurrentUser));
    }

    /**
     * Updates risk parameters and Agent 1 tier weights for the given profile.
     * Validates that tier weights sum to 1.0000 before persisting.
     * Writes one audit row capturing the before/after snapshot.
     */
    @PutMapping("/me/profile/{profileId}")
    public ResponseEntity<UserProfileResponseDto> updateProfile(
            @PathVariable UUID profileId,
            @Valid @RequestBody UpdateUserProfileRequestDto request) {
        // Phase 5 IDOR fix: a user may only edit their own profile (admin may edit any). 401/403.
        guard.requireOwner(profileId);
        return ResponseEntity.ok(userProfileService.updateProfile(profileId, request));
    }

    /**
     * Returns the last 50 profile change audit entries for the given profile, newest first.
     */
    @GetMapping("/me/profile/{profileId}/audit")
    public ResponseEntity<List<UserProfileAuditDto>> getAudit(@PathVariable UUID profileId) {
        // Phase 5 IDOR fix: a user may only read their own profile audit (admin may read any).
        guard.requireOwner(profileId);
        return ResponseEntity.ok(userProfileService.getAudit(profileId));
    }
}
