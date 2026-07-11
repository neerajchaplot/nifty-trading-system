package com.the3Cgrp.zupptrade.agentUser.service;

import com.the3Cgrp.zupptrade.agentUser.domain.UserProfileAuditEntity;
import com.the3Cgrp.zupptrade.agentUser.domain.UserProfileEntity;
import com.the3Cgrp.zupptrade.agentUser.dto.UpdateUserProfileRequestDto;
import com.the3Cgrp.zupptrade.agentUser.dto.UserProfileAuditDto;
import com.the3Cgrp.zupptrade.agentUser.dto.UserProfileResponseDto;
import com.the3Cgrp.zupptrade.agentUser.repository.UserProfileAuditRepository;
import com.the3Cgrp.zupptrade.agentUser.repository.UserProfileRepository;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxProfileClient;
import com.the3Cgrp.zupptrade.core.upstox.model.UpstoxUserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

    private static final String DEFAULT_USER_ID = "default";

    private static final BigDecimal DEFAULT_CAPITAL      = new BigDecimal("500000.00");
    private static final BigDecimal DEFAULT_MIN_POP      = new BigDecimal("0.80");
    private static final BigDecimal DEFAULT_MAX_LOSS_PCT = new BigDecimal("1.50");
    private static final BigDecimal DEFAULT_MAX_POP_GAP  = new BigDecimal("15.00");
    private static final BigDecimal DEFAULT_MIN_ROC_PCT  = new BigDecimal("0.50");
    private static final int        DEFAULT_SPREAD_MIN   = 50;
    private static final int        DEFAULT_SPREAD_MAX   = 150;
    private static final BigDecimal DEFAULT_W1A          = new BigDecimal("0.3000");
    private static final BigDecimal DEFAULT_W1B          = new BigDecimal("0.2000");
    private static final BigDecimal DEFAULT_W2           = new BigDecimal("0.3000");
    private static final BigDecimal DEFAULT_W3           = new BigDecimal("0.1000");
    private static final BigDecimal DEFAULT_W4           = new BigDecimal("0.1000");
    private static final BigDecimal WEIGHT_SUM_EXPECTED  = new BigDecimal("1.0000");

    private final UpstoxProfileClient upstoxProfileClient;
    private final UserProfileRepository repository;
    private final UserProfileAuditRepository auditRepository;
    private final ObjectMapper objectMapper;

    public UserProfileService(UpstoxProfileClient upstoxProfileClient,
                              UserProfileRepository repository,
                              UserProfileAuditRepository auditRepository,
                              ObjectMapper objectMapper) {
        this.upstoxProfileClient = upstoxProfileClient;
        this.repository          = repository;
        this.auditRepository     = auditRepository;
        this.objectMapper        = objectMapper;
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

        UserProfileEntity entity = repository.findByUserId(upstoxUserId)
                .orElseGet(() -> claimDefaultOrCreate(upstoxUserId));

        return toDto(entity);
    }

    /**
     * Updates the current user's profile. Writes one audit row comparing old vs new snapshot.
     * Validates that tier weights sum to exactly 1.0000 before persisting.
     */
    @Transactional
    public UserProfileResponseDto updateProfile(UUID profileId, UpdateUserProfileRequestDto request) {
        validateWeightSum(request);

        UserProfileEntity existing = repository.findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("User profile not found: " + profileId));

        String oldSnapshot = toSnapshot(existing);

        existing.setCapital(request.capital());
        existing.setMinPop(request.minPop());
        existing.setMaxLossPct(request.maxLossPct());
        existing.setMaxPopPoppGap(request.maxPopPoppGap());
        existing.setMinRocPct(request.minRocPct());
        existing.setSpreadWidthMin(request.spreadWidthMin());
        existing.setSpreadWidthMax(request.spreadWidthMax());
        existing.setTier1aWeight(request.tier1aWeight());
        existing.setTier1bWeight(request.tier1bWeight());
        existing.setTier2Weight(request.tier2Weight());
        existing.setTier3Weight(request.tier3Weight());
        existing.setTier4Weight(request.tier4Weight());

        UserProfileEntity saved = repository.save(existing);

        String newSnapshot = toSnapshot(saved);
        writeAudit(profileId, oldSnapshot, newSnapshot);

        log.info("agent-user.profile.updated profileId={}", profileId);
        return toDto(saved);
    }

    /** Returns the last 50 audit entries for the given profile, newest first. */
    @Transactional(readOnly = true)
    public List<UserProfileAuditDto> getAudit(UUID profileId) {
        return auditRepository.findTop50ByUserProfileIdOrderByChangedAtDesc(profileId)
                .stream()
                .map(this::toAuditDto)
                .toList();
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private void validateWeightSum(UpdateUserProfileRequestDto r) {
        BigDecimal sum = r.tier1aWeight()
                .add(r.tier1bWeight())
                .add(r.tier2Weight())
                .add(r.tier3Weight())
                .add(r.tier4Weight())
                .setScale(4, RoundingMode.HALF_UP);
        if (sum.compareTo(WEIGHT_SUM_EXPECTED) != 0) {
            throw new IllegalArgumentException(
                    "Tier weights must sum to 1.0000, got: " + sum);
        }
    }

    private String toSnapshot(UserProfileEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("capital",       e.getCapital());
        m.put("minPop",        e.getMinPop());
        m.put("maxLossPct",    e.getMaxLossPct());
        m.put("maxPopPoppGap", e.getMaxPopPoppGap());
        m.put("minRocPct",     e.getMinRocPct());
        m.put("spreadWidthMin",e.getSpreadWidthMin());
        m.put("spreadWidthMax",e.getSpreadWidthMax());
        m.put("tier1aWeight",  e.getTier1aWeight());
        m.put("tier1bWeight",  e.getTier1bWeight());
        m.put("tier2Weight",   e.getTier2Weight());
        m.put("tier3Weight",   e.getTier3Weight());
        m.put("tier4Weight",   e.getTier4Weight());
        try {
            return objectMapper.writeValueAsString(m);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize profile snapshot", ex);
        }
    }

    private void writeAudit(UUID profileId, String oldSnapshot, String newSnapshot) {
        UserProfileAuditEntity audit = new UserProfileAuditEntity();
        audit.setUserProfileId(profileId);
        audit.setOldValues(oldSnapshot);
        audit.setNewValues(newSnapshot);
        auditRepository.save(audit);
    }

    private UserProfileAuditDto toAuditDto(UserProfileAuditEntity e) {
        TypeReference<Map<String, Object>> mapType = new TypeReference<>() {};
        try {
            Map<String, Object> oldMap = objectMapper.readValue(e.getOldValues(), mapType);
            Map<String, Object> newMap = objectMapper.readValue(e.getNewValues(), mapType);
            return new UserProfileAuditDto(e.getId(), e.getChangedAt(), oldMap, newMap);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to deserialize audit entry: " + e.getId(), ex);
        }
    }

    private UserProfileEntity claimDefaultOrCreate(String upstoxUserId) {
        return repository.findByUserId(DEFAULT_USER_ID)
                .map(placeholder -> {
                    placeholder.setUserId(upstoxUserId);
                    UserProfileEntity saved = repository.save(placeholder);
                    log.info("agent-user.claimed.default userId={} profileId={}", upstoxUserId, saved.getId());
                    return saved;
                })
                .orElseGet(() -> {
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
        e.setMinRocPct(DEFAULT_MIN_ROC_PCT);
        e.setSpreadWidthMin(DEFAULT_SPREAD_MIN);
        e.setSpreadWidthMax(DEFAULT_SPREAD_MAX);
        e.setTier1aWeight(DEFAULT_W1A);
        e.setTier1bWeight(DEFAULT_W1B);
        e.setTier2Weight(DEFAULT_W2);
        e.setTier3Weight(DEFAULT_W3);
        e.setTier4Weight(DEFAULT_W4);
        return e;
    }

    private UserProfileResponseDto toDto(UserProfileEntity e) {
        return new UserProfileResponseDto(
                e.getId(),
                e.getUserId(),
                e.getCapital(),
                e.getMinPop(),
                e.getMaxLossPct(),
                e.getMaxPopPoppGap(),
                e.getMinRocPct(),
                e.getSpreadWidthMin(),
                e.getSpreadWidthMax(),
                e.getTier1aWeight(),
                e.getTier1bWeight(),
                e.getTier2Weight(),
                e.getTier3Weight(),
                e.getTier4Weight()
        );
    }
}
