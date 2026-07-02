package com.the3Cgrp.zupptrade.agentUser.service;

import com.the3Cgrp.zupptrade.agentUser.domain.UserProfileEntity;
import com.the3Cgrp.zupptrade.agentUser.dto.UserProfileResponseDto;
import com.the3Cgrp.zupptrade.agentUser.repository.UserProfileRepository;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxProfileClient;
import com.the3Cgrp.zupptrade.core.upstox.model.UpstoxUserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

    private static final String DEFAULT_USER_ID = "default";

    // Sensible defaults for auto-created profiles — user can edit via future profile page
    private static final BigDecimal DEFAULT_CAPITAL        = new BigDecimal("500000.00"); // ₹5 Lakhs
    private static final BigDecimal DEFAULT_MIN_POP        = new BigDecimal("0.80");
    private static final BigDecimal DEFAULT_MAX_LOSS_PCT   = new BigDecimal("1.50");
    private static final BigDecimal DEFAULT_MAX_POP_GAP    = new BigDecimal("15.00");
    private static final int        DEFAULT_SPREAD_MIN     = 50;
    private static final int        DEFAULT_SPREAD_MAX     = 150;

    private final UpstoxProfileClient upstoxProfileClient;
    private final UserProfileRepository repository;

    public UserProfileService(UpstoxProfileClient upstoxProfileClient,
                              UserProfileRepository repository) {
        this.upstoxProfileClient = upstoxProfileClient;
        this.repository = repository;
    }

    /**
     * Resolves the current Upstox user to a user_profiles row.
     *
     * Resolution order:
     *   1. Look up by Upstox userId — happy path after first run.
     *   2. Claim the placeholder "default" row — updates user_id in place.
     *   3. Auto-create a new row with sensible defaults — first-ever run.
     */
    @Transactional
    public UserProfileResponseDto findOrCreateForCurrentUser() {
        UpstoxUserProfile upstoxProfile = upstoxProfileClient.getProfile();
        String upstoxUserId = upstoxProfile.userId();

        log.info("agent-user.resolve userId={}", upstoxUserId);

        // 1 — already exists for this Upstox user
        UserProfileEntity entity = repository.findByUserId(upstoxUserId)
                .orElseGet(() -> claimDefaultOrCreate(upstoxUserId));

        return toDto(entity);
    }

    private UserProfileEntity claimDefaultOrCreate(String upstoxUserId) {
        return repository.findByUserId(DEFAULT_USER_ID)
                .map(placeholder -> {
                    // 2 — claim the seeded placeholder row
                    placeholder.setUserId(upstoxUserId);
                    UserProfileEntity saved = repository.save(placeholder);
                    log.info("agent-user.claimed.default userId={} profileId={}", upstoxUserId, saved.getId());
                    return saved;
                })
                .orElseGet(() -> {
                    // 3 — first-ever run, no row exists at all — auto-create with defaults
                    UserProfileEntity created = buildDefault(upstoxUserId);
                    UserProfileEntity saved = repository.save(created);
                    log.info("agent-user.created.new userId={} profileId={}", upstoxUserId, saved.getId());
                    return saved;
                });
    }

    private UserProfileEntity buildDefault(String userId) {
        UserProfileEntity e = new UserProfileEntity();
        e.setUserId(userId);
        e.setCapital(DEFAULT_CAPITAL);
        e.setMinPop(DEFAULT_MIN_POP);
        e.setMaxLossPct(DEFAULT_MAX_LOSS_PCT);
        e.setMaxPopPoppGap(DEFAULT_MAX_POP_GAP);
        e.setSpreadWidthMin(DEFAULT_SPREAD_MIN);
        e.setSpreadWidthMax(DEFAULT_SPREAD_MAX);
        return e;
    }

    private UserProfileResponseDto toDto(UserProfileEntity e) {
        return new UserProfileResponseDto(e.getId(), e.getUserId(), e.getCapital());
    }
}
