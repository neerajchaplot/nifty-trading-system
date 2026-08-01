package com.the3Cgrp.zupptrade.shared.simulation;

import com.the3Cgrp.zupptrade.shared.enums.OptionType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Reads a scenario folder ({@code test-data/simulation/scenarios/<name>/}) and serves each
 * market data point "as of" a virtual instant. Plain Java — no Spring — so every agent module
 * (all depend on shared-domain) reuses it under the simulation profile.
 *
 * Timelines are step functions: the most recent row at-or-before the virtual time/date wins.
 * Missing optional files (candles, fii_dii, commentary, sentiment) yield empty results.
 * The folder is read once at construction; instances are immutable and thread-safe.
 */
public final class ScenarioReader {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final Path dir;
    private final List<ScenarioData.SpotVix> spotVix;
    private final List<ScenarioData.Strike>  chain;
    private final List<ScenarioData.Candle>  candles;
    private final List<ScenarioData.FiiDii>  fiiDii;

    public ScenarioReader(Path scenarioDir) {
        if (!Files.isDirectory(scenarioDir)) {
            throw new IllegalArgumentException("Scenario folder not found: " + scenarioDir.toAbsolutePath());
        }
        this.dir     = scenarioDir;
        this.spotVix = loadSpotVix();
        this.chain   = loadChain();
        this.candles = loadCandles();
        this.fiiDii  = loadFiiDii();
    }

    public Path dir() { return dir; }

    /** Spot + VIX at-or-before the virtual instant. */
    public Optional<ScenarioData.SpotVix> spotVixAt(Instant t) {
        return spotVix.stream()
                .filter(r -> !r.time().isAfter(t))
                .max(Comparator.comparing(ScenarioData.SpotVix::time));
    }

    /** A specific option leg (strike + type) for the virtual instant's trading date. */
    public Optional<ScenarioData.Strike> strikeAt(Instant t, int strike, OptionType type) {
        LocalDate d = t.atZone(IST).toLocalDate();
        return chain.stream()
                .filter(s -> s.strike() == strike && s.type() == type && !s.date().isAfter(d))
                .max(Comparator.comparing(ScenarioData.Strike::date));
    }

    /** All strikes for the latest chain date at-or-before the virtual instant. */
    public List<ScenarioData.Strike> optionChainAt(Instant t) {
        LocalDate d = t.atZone(IST).toLocalDate();
        Optional<LocalDate> asOf = chain.stream()
                .map(ScenarioData.Strike::date)
                .filter(cd -> !cd.isAfter(d))
                .max(Comparator.naturalOrder());
        return asOf.map(day -> chain.stream().filter(s -> s.date().equals(day)).toList())
                   .orElseGet(List::of);
    }

    /** Daily candles up to (and including) the virtual date, oldest first, capped to maxDays. */
    public List<ScenarioData.Candle> candlesUpTo(Instant t, int maxDays) {
        LocalDate d = t.atZone(IST).toLocalDate();
        List<ScenarioData.Candle> upTo = candles.stream()
                .filter(c -> !c.date().isAfter(d))
                .sorted(Comparator.comparing(ScenarioData.Candle::date))
                .toList();
        int from = Math.max(0, upTo.size() - maxDays);
        return upTo.subList(from, upTo.size());
    }

    /** FII/DII for the latest date at-or-before the virtual instant. */
    public Optional<ScenarioData.FiiDii> fiiDiiAt(Instant t) {
        LocalDate d = t.atZone(IST).toLocalDate();
        return fiiDii.stream()
                .filter(r -> !r.date().isAfter(d))
                .max(Comparator.comparing(ScenarioData.FiiDii::date));
    }

    public Optional<String> commentary() {
        return readText("commentary.txt");
    }

    public Optional<BigDecimal> sentiment() {
        return readText("sentiment.txt").map(String::strip).map(BigDecimal::new);
    }

    // ── loaders ───────────────────────────────────────────────────────────────

    private List<ScenarioData.SpotVix> loadSpotVix() {
        List<ScenarioData.SpotVix> out = new ArrayList<>();
        for (String[] f : readCsv("spot_vix.csv")) {
            out.add(new ScenarioData.SpotVix(
                    OffsetDateTime.parse(f[0]).toInstant(), new BigDecimal(f[1]), new BigDecimal(f[2])));
        }
        return out;
    }

    private List<ScenarioData.Strike> loadChain() {
        List<ScenarioData.Strike> out = new ArrayList<>();
        for (String[] f : readCsv("option_chain.csv")) {
            out.add(new ScenarioData.Strike(
                    LocalDate.parse(f[0]), Integer.parseInt(f[1]), OptionType.valueOf(f[2]),
                    new BigDecimal(f[3]), new BigDecimal(f[4]), new BigDecimal(f[5]),
                    Long.parseLong(f[6]), new BigDecimal(f[7])));
        }
        return out;
    }

    private List<ScenarioData.Candle> loadCandles() {
        List<ScenarioData.Candle> out = new ArrayList<>();
        for (String[] f : readCsv("candles.csv")) {
            out.add(new ScenarioData.Candle(LocalDate.parse(f[0]),
                    new BigDecimal(f[1]), new BigDecimal(f[2]), new BigDecimal(f[3]), new BigDecimal(f[4])));
        }
        return out;
    }

    private List<ScenarioData.FiiDii> loadFiiDii() {
        List<ScenarioData.FiiDii> out = new ArrayList<>();
        for (String[] f : readCsv("fii_dii.csv")) {
            out.add(new ScenarioData.FiiDii(LocalDate.parse(f[0]),
                    new BigDecimal(f[1]), new BigDecimal(f[2]), new BigDecimal(f[3]), new BigDecimal(f[4])));
        }
        return out;
    }

    // ── csv / text helpers ────────────────────────────────────────────────────

    /** Reads a CSV; skips blank lines and '#' comments, drops the first real (header) line, trims fields. */
    private List<String[]> readCsv(String fileName) {
        Path p = dir.resolve(fileName);
        if (!Files.isRegularFile(p)) return List.of();
        List<String[]> rows = new ArrayList<>();
        boolean headerSkipped = false;
        try {
            for (String line : Files.readAllLines(p)) {
                String s = line.strip();
                if (s.isEmpty() || s.startsWith("#")) continue;
                if (!headerSkipped) { headerSkipped = true; continue; }  // first non-comment line is the header
                String[] parts = s.split(",");
                for (int i = 0; i < parts.length; i++) parts[i] = parts[i].strip();
                rows.add(parts);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + p, e);
        }
        return rows;
    }

    private Optional<String> readText(String fileName) {
        Path p = dir.resolve(fileName);
        if (!Files.isRegularFile(p)) return Optional.empty();
        try {
            String content = Files.readString(p).strip();
            return content.isEmpty() ? Optional.empty() : Optional.of(content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + p, e);
        }
    }
}
