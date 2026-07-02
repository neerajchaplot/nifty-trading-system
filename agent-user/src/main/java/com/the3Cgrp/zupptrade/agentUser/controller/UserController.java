package com.the3Cgrp.zupptrade.agentUser.controller;

import com.the3Cgrp.zupptrade.agentUser.dto.UserProfileResponseDto;
import com.the3Cgrp.zupptrade.agentUser.service.UserProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
     */
    @GetMapping("/me")
    public ResponseEntity<UserProfileResponseDto> me() {
        return ResponseEntity.ok(userProfileService.findOrCreateForCurrentUser());
    }
}
