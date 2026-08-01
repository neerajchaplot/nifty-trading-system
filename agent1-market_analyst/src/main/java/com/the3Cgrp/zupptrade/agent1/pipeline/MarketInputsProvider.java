package com.the3Cgrp.zupptrade.agent1.pipeline;

import com.the3Cgrp.zupptrade.agent1.domain.model.MarketInputs;
import com.the3Cgrp.zupptrade.agent1.dto.ScoreRequestDto;

import java.time.LocalDate;

/**
 * Single seam between the scoring pipeline and where market inputs come from.
 *
 * <p>Strategy Pattern: the live implementation fetches from Upstox / Marketaux / the LLM;
 * the simulation implementation reads a scenario folder. {@link ScoringPipeline} depends only
 * on this interface, so the scoring logic is identical in both modes — only the data source
 * changes, gated by the {@code simulation} Spring profile.
 */
public interface MarketInputsProvider {

    /**
     * Assemble the full {@link MarketInputs} snapshot for one scoring run.
     * Implementations must never throw for missing data — a missing input becomes a {@code null}
     * field, which the tier scorers treat as a neutral (0) vote.
     *
     * @param request    the score request (commentary, marketaux flag)
     * @param expiryDate the resolved expiry the run is scoring for
     */
    MarketInputs fetch(ScoreRequestDto request, LocalDate expiryDate);
}
