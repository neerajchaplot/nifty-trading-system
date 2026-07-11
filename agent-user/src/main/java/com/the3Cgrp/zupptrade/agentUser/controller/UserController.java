package com.the3Cgrp.zupptrade.agentUser.controller;

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

    public UserController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    /**
     * Resolves the current Upstox identity to the internal user_profiles UUID.
     * Called by the UI on load. Creates or claims the profile row on first run.
     * Returns the full profile including all risk parameters and Agent 1 weights.
     */
    @GetMapping("/me")
    public ResponseEntity<UserProfileResponseDto> me() {
        return ResponseEntity.ok(userProfileService.findOrCreateForCurrentUser());
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
        return ResponseEntity.ok(userProfileService.updateProfile(profileId, request));
    }

    /**
     * Returns the last 50 profile change audit entries for the given profile, newest first.
     */
    @GetMapping("/me/profile/{profileId}/audit")
    public ResponseEntity<List<UserProfileAuditDto>> getAudit(@PathVariable UUID profileId) {
        return ResponseEntity.ok(userProfileService.getAudit(profileId));
    }
}
