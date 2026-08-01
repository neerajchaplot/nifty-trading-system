package com.the3Cgrp.zupptrade.agent1.pipeline;

import com.the3Cgrp.zupptrade.agent1.domain.model.CommentarySignal;
import com.the3Cgrp.zupptrade.agent1.domain.model.MarketInputs;
import com.the3Cgrp.zupptrade.agent1.domain.model.OhlcCandle;
import com.the3Cgrp.zupptrade.agent1.domain.model.PrecomputedIndicators;
import com.the3Cgrp.zupptrade.agent1.dto.ScoreRequestDto;
import com.the3Cgrp.zupptrade.agent1.service.TechnicalIndicatorService;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import com.the3Cgrp.zupptrade.shared.enums.VixRegime;
import com.the3Cgrp.zupptrade.shared.simulation.ScenarioData;
import com.the3Cgrp.zupptrade.shared.simulation.ScenarioReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Simulation {@link MarketInputsProvider}: builds the same {@link MarketInputs} the live provider
 * does, but every field is derived from the scenario folder (via {@link ScenarioReader}) as of the
 * fixed simulation {@link Clock} instant — never from Upstox/Marketaux/the LLM. Active only under
 * the {@code simulation} profile.
 *
 * <p>Deliberately unsupported in v1 (score as neutral, exactly as a live data-gap would):
 * <ul>
 *   <li>Gift Nifty premium — no pre-market feed in a folder (Tier 3 signal → null → 0 vote)</li>
 *   <li>LLM commentary — no model call in sim; {@code commentaryBias=null} makes Tier 4 take the
 *       folder's Marketaux sentiment at face value (per the CLAUDE.md Tier 4 rule)</li>
 *   <li>5-day FII trend — needs the persisted snapshot history; left null (context only, not scored)</li>
 * </ul>
 */
@Component
@Profile("simulation")
public class SimulatedMarketInputsProvider implements MarketInputsProvider {

    private static final Logger log = LoggerFactory.getLogger(SimulatedMarketInputsProvider.class);

    // Same VIX regime cutoffs the live UpstoxOptionChainClient uses (per-agent tunables, not shared).
    private static final BigDecimal VIX_LOW    = new BigDecimal("13");
    private static final BigDecimal VIX_NORMAL = new BigDecimal("18");
    private static final BigDecimal VIX_HIGH   = new BigDecimal("24");

    private final ScenarioReader reader;
    private final Clock clock;
    private final TechnicalIndicatorService technicalIndicatorService;

    public SimulatedMarketInputsProvider(ScenarioReader reader,
                                         Clock clock,
                                         TechnicalIndicatorService technicalIndicatorService) {
        this.reader = reader;
        this.clock = clock;
        this.technicalIndicatorService = technicalIndicatorService;
    }

    @Override
    public MarketInputs fetch(ScoreRequestDto request, LocalDate expiryDate) {
        Instant asOf = clock.instant();

        // Spot + VIX at the entry instant, and the previous session's VIX for Tier 3's daily change.
        BigDecimal spot = reader.spotVixAt(asOf).map(ScenarioData.SpotVix::spot).orElse(null);
        BigDecimal vix  = reader.spotVixAt(asOf).map(ScenarioData.SpotVix::vix).orElse(null);
        BigDecimal vixPrev = reader.spotVixAt(asOf.minus(Duration.ofDays(1)))
                .map(ScenarioData.SpotVix::vix).orElse(null);
        VixRegime vixRegime = classifyVix(vix);

        // Option chain snapshot → PCR, max pain, and day-over-day OI change (Tier 1A + Tier 3).
        List<ScenarioData.Strike> chain = reader.optionChainAt(asOf);
        BigDecimal pcr = pcr(chain);
        Integer maxPain = maxPain(chain);
        List<ScenarioData.Strike> prevChain = reader.optionChainAt(asOf.minus(Duration.ofDays(1)));
        BigDecimal callOiChange = oiChange(chain, prevChain, OptionType.CE);
        BigDecimal putOiChange  = oiChange(chain, prevChain, OptionType.PE);

        // FII/DII for the session at-or-before the entry date (Tier 2).
        ScenarioData.FiiDii fii = reader.fiiDiiAt(asOf).orElse(null);

        // TA4J indicators from the folder's candle history (Tier 1A price structure + Tier 1B).
        List<OhlcCandle> candles = reader.candlesUpTo(asOf, 200).stream()
                .map(c -> new OhlcCandle(c.date(), c.open(), c.high(), c.low(), c.close(), 0L))
                .toList();
        PrecomputedIndicators indicators = technicalIndicatorService.compute(candles);

        // News sentiment from the folder (Tier 4). No LLM in sim → commentaryBias stays null.
        BigDecimal marketauxSentiment = reader.sentiment().orElse(null);

        log.warn("SIMULATION (agent1) inputs @ {} spot={} vix={} vixPrev={} regime={} pcr={} maxPain={} "
                        + "fiiFut={} fiiOpt={} dii={} sentiment={} candles={} expiry={}",
                asOf, spot, vix, vixPrev, vixRegime, pcr, maxPain,
                fii != null ? fii.fiiFutNet() : null,
                fii != null ? fii.fiiOptNet() : null,
                fii != null ? fii.diiNet() : null,
                marketauxSentiment, candles.size(), expiryDate);

        return MarketInputs.builder()
                .spot(spot)
                .futuresPremium(null)              // excluded from scoring; not simulated
                .pcr(pcr)
                .maxPain(maxPain)
                .vixLevel(vix)
                .vixPrevLevel(vixPrev)
                .vixRegime(vixRegime)
                .fiiNetFutures(fii != null ? fii.fiiFutNet() : null)
                .fiiNetOptions(fii != null ? fii.fiiOptNet() : null)
                .diiNet(fii != null ? fii.diiNet() : null)
                .fiiLongRatio(fii != null ? fii.fiiLongRatio() : null)
                .fiiTrend(null)                    // 5-day trend needs snapshot history — context only
                .callOiChange(callOiChange)
                .putOiChange(putOiChange)
                .giftNiftyPremium(null)            // no pre-market feed in a folder
                .marketauxSentiment(marketauxSentiment)
                .marketauxDetails(null)
                .commentaryBias(null)              // no LLM → Tier 4 takes Marketaux at face value
                .commentarySignal(CommentarySignal.neutral())
                .indicators(indicators)
                .expiryDate(expiryDate)
                .build();
    }

    private VixRegime classifyVix(BigDecimal vix) {
        if (vix == null) return VixRegime.NORMAL;
        if (vix.compareTo(VIX_LOW) < 0)    return VixRegime.LOW;
        if (vix.compareTo(VIX_NORMAL) < 0) return VixRegime.NORMAL;
        if (vix.compareTo(VIX_HIGH) < 0)   return VixRegime.HIGH;
        return VixRegime.EXTREME;
    }

    /** Put-Call OI Ratio = total PE open interest / total CE open interest. */
    private BigDecimal pcr(List<ScenarioData.Strike> chain) {
        if (chain == null || chain.isEmpty()) return null;
        long callOi = chain.stream().filter(s -> s.type() == OptionType.CE).mapToLong(ScenarioData.Strike::oi).sum();
        long putOi  = chain.stream().filter(s -> s.type() == OptionType.PE).mapToLong(ScenarioData.Strike::oi).sum();
        if (callOi == 0) return null;
        return BigDecimal.valueOf(putOi).divide(BigDecimal.valueOf(callOi), 4, RoundingMode.HALF_UP);
    }

    /**
     * Max pain = the expiry price that minimises total option-writer payout across the chain.
     * For each candidate strike S: callPain = Σ max(0, S−K)·CE_OI(K); putPain = Σ max(0, K−S)·PE_OI(K).
     */
    private Integer maxPain(List<ScenarioData.Strike> chain) {
        if (chain == null || chain.isEmpty()) return null;
        Integer best = null;
        BigDecimal bestPain = null;
        for (ScenarioData.Strike candidate : chain) {
            int s = candidate.strike();
            BigDecimal pain = BigDecimal.ZERO;
            for (ScenarioData.Strike leg : chain) {
                BigDecimal oi = BigDecimal.valueOf(leg.oi());
                if (leg.type() == OptionType.CE && s > leg.strike()) {
                    pain = pain.add(BigDecimal.valueOf(s - leg.strike()).multiply(oi));
                } else if (leg.type() == OptionType.PE && leg.strike() > s) {
                    pain = pain.add(BigDecimal.valueOf(leg.strike() - s).multiply(oi));
                }
            }
            if (bestPain == null || pain.compareTo(bestPain) < 0) {
                bestPain = pain;
                best = s;
            }
        }
        return best;
    }

    /** Day-over-day change in total OI for one option type; null when there is no prior snapshot. */
    private BigDecimal oiChange(List<ScenarioData.Strike> today, List<ScenarioData.Strike> prev, OptionType type) {
        if (today == null || today.isEmpty() || prev == null || prev.isEmpty()) return null;
        long now  = today.stream().filter(s -> s.type() == type).mapToLong(ScenarioData.Strike::oi).sum();
        long then = prev.stream().filter(s -> s.type() == type).mapToLong(ScenarioData.Strike::oi).sum();
        return BigDecimal.valueOf(now - then);
    }
}
