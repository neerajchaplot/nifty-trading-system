package com.the3Cgrp.zupptrade.agent5;

import com.the3Cgrp.zupptrade.agent5.client.UpstoxOrderClient;
import com.the3Cgrp.zupptrade.agent5.client.request.PlaceOrderV3Request;
import com.the3Cgrp.zupptrade.agent5.client.response.OrderStatusResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.PlaceOrderV3Response;
import com.the3Cgrp.zupptrade.agent5.client.response.TaggedOrdersResponse;
import com.the3Cgrp.zupptrade.agent5.service.OrderTagBuilder;
import com.the3Cgrp.zupptrade.core.upstox.auth.UpstoxTokenHolder;
import com.the3Cgrp.zupptrade.core.upstox.config.UpstoxProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DIAGNOSTIC PROBE — answers, empirically, whether the Upstox SANDBOX can run our real order flow
 * for weekly NIFTY options, so we can decide whether `simulate-fills` can be turned off.
 *
 * It is NOT a pass/fail test — it logs everything under the "sandbox.probe.*" markers and only
 * fails if it literally cannot obtain an instrument to try. Read the console output.
 *
 * What it checks, in order:
 *   1. Fetch a REAL weekly NIFTY option instrument_key from the PRODUCTION option-contract API
 *      (proves the real key format `NSE_FO|<n>` and gives us something the exchange knows).
 *   2. Place ONE LIMIT BUY for that key in the SANDBOX (via the same UpstoxOrderClient the app uses).
 *      → does sandbox ACCEPT the weekly-NIFTY instrument, or reject it? (instrument-master parity)
 *   3. Poll GET /v2/order/details a few times.
 *      → does the order become `complete` (sandbox fills) or stay `open` (needs simulate-fills)?
 *      → does the order-status read even work in sandbox?
 *   4. Query GET /v2/order/details?tag=… .
 *      → does the by-tag reconcile query work in sandbox?
 *   5. Cancel the order if still open (cleanup — no real money either way).
 *
 * Run:
 *   mvn test -pl agent5-execution "-Dexcluded.test.groups=" -Dgroups=sandbox ^
 *     -Dspring.profiles.active=sandbox,local -Dtest=SandboxCapabilityProbeIT
 *
 * Prereqs (env): TOKEN_ENCRYPTION_KEY, UPSTOX_ACCESS_TOKEN (prod, for the contract fetch),
 *   UPSTOX_SANDBOX_TOKEN (sandbox, for order placement), NeonDB reachable (local profile).
 */
@Tag("sandbox")
@SpringBootTest
@ActiveProfiles({"sandbox", "local"})
class SandboxCapabilityProbeIT {

    private static final Logger log = LoggerFactory.getLogger(SandboxCapabilityProbeIT.class);

    private static final String NIFTY_INDEX_KEY = "NSE_INDEX|Nifty 50";

    @Autowired private UpstoxOrderClient orderClient;
    @Autowired private UpstoxTokenHolder tokenHolder;   // production token
    @Autowired private UpstoxProperties  upstoxProps;   // orderAccessToken = sandbox token

    /** Market-data / production RestClient (api.upstox.com, production token) — for the contract fetch. */
    @Autowired
    @Qualifier("upstoxRestClient")
    private RestClient marketClient;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void probe_sandboxWeeklyNiftyPlacementAndFill() throws Exception {
        UUID probeTradeId = UUID.randomUUID();
        String tag = OrderTagBuilder.entryTag(probeTradeId, 0);   // unique per-leg tag (v3 has no correlation_id)
        log.info("sandbox.probe.start tradeId={} tag={}", probeTradeId, tag);

        // ── STEP 0: which tokens are actually loaded in THIS JVM? ────────────────────────────
        // prodToken → market data / margin (api.upstox.com). orderAccessToken → sandbox orders.
        // If orderAccessToken is blank, the order client falls back to the PROD token against the
        // sandbox host → guaranteed 401. So a blank sandbox token here explains a placement 401.
        String prodTok  = tokenHolder.getToken();
        String orderTok = upstoxProps.getOrderAccessToken();
        boolean sameValue = prodTok != null && prodTok.equals(orderTok);
        // SHA-256 fingerprint (first 12 hex) + masked ends — proves value equality WITHOUT exposing the token.
        log.info("sandbox.probe.tokens prodLen={} prodFp={} prodMasked={} sandboxLen={} sandboxFp={} sandboxMasked={} sameValue={}",
                prodTok == null ? 0 : prodTok.length(), fingerprint(prodTok), masked(prodTok),
                orderTok == null ? 0 : orderTok.length(), fingerprint(orderTok), masked(orderTok), sameValue);
        if (sameValue) {
            log.warn("sandbox.probe.tokens.SAME_VALUE — the sandbox order token has the SAME VALUE as the "
                    + "production token (identical fingerprints). Upstox sandbox REJECTS production tokens (→ 401). "
                    + "Set UPSTOX_SANDBOX_TOKEN to a DISTINCT token from a Sandbox App in the developer dashboard.");
        }
        // Opt-in full print for copy/paste into Postman etc. — SECRET; only when -Dprobe.printFullToken=true.
        if (Boolean.getBoolean("probe.printFullToken")) {
            log.warn("sandbox.probe.tokens.FULL — SECRET, do NOT share or commit. sandboxToken={}", orderTok);
            log.warn("sandbox.probe.tokens.FULL — SECRET, do NOT share or commit. prodToken={}", prodTok);
        }

        // ── STEP 1: get a real weekly NIFTY option instrument_key from PRODUCTION ────────────
        Contract c = fetchNearestWeeklyPut();
        if (c == null) {
            log.error("sandbox.probe.ABORT — could not fetch any NIFTY option contract from production. "
                    + "Check UPSTOX_ACCESS_TOKEN and market connectivity.");
            org.junit.jupiter.api.Assertions.fail("Could not fetch a NIFTY option instrument_key to probe with");
            return;
        }
        log.info("sandbox.probe.instrument expiry={} strike={} type={} instrumentKey={} lotSize={}",
                c.expiry, c.strike, c.type, c.instrumentKey, c.lotSize);

        // ── STEP 2: place ONE BUY order in the SANDBOX (v3) ─────────────────────────────────
        // Sandbox involves no real money and never touches the real exchange, so place at the REAL
        // live LTP (marketable) to genuinely test whether it fills — not a token ₹1.
        //   -Dprobe.orderType=MARKET  → order type MARKET (no price dependency)
        //   -Dprobe.limitPrice=<px>   → explicit LIMIT price (overrides the fetched LTP)
        String orderType     = System.getProperty("probe.orderType", "LIMIT");
        String priceOverride = System.getProperty("probe.limitPrice");   // optional
        BigDecimal ltp   = fetchLtp(c.instrumentKey);
        BigDecimal price = priceOverride != null ? new BigDecimal(priceOverride)
                : (ltp != null ? ltp : new BigDecimal("1.00"));
        log.info("sandbox.probe.order orderType={} ltp={} limitPrice={}", orderType, ltp, price);
        PlaceOrderV3Request order = "MARKET".equalsIgnoreCase(orderType)
                ? PlaceOrderV3Request.market(c.instrumentKey, "BUY", "D", c.lotSize, tag)
                : PlaceOrderV3Request.limit(c.instrumentKey, "BUY", "D", c.lotSize, price, tag);
        PlaceOrderV3Response placed;
        try {
            placed = orderClient.placeOrder(order);
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            boolean authError = msg.contains("401") || msg.toUpperCase().contains("UNAUTHORIZED");
            if (authError) {
                // 401 is an AUTH failure, NOT an instrument problem. The sandbox order token is
                // invalid/missing (or the client fell back to the prod token — see sandbox.probe.tokens).
                log.error("sandbox.probe.place.FAILED — 401 UNAUTHORIZED on the SANDBOX order host. "
                        + "This is an AUTH problem, not an instrument problem: fix UPSTOX_SANDBOX_TOKEN "
                        + "(a valid 30-day sandbox-app token). error={}", msg);
                log.info("sandbox.probe.CONCLUSION INCONCLUSIVE — sandbox token invalid/missing; fix it and re-run");
            } else {
                log.error("sandbox.probe.place.FAILED — sandbox rejected the order (NOT an auth error). "
                        + "Likely the instrument is not in the sandbox master. error={}", msg);
                log.info("sandbox.probe.CONCLUSION simulate-fills likely must stay TRUE (sandbox rejected the instrument)");
            }
            return;
        }
        log.info("sandbox.probe.place.result apiStatus={} orderIds={}", placed.status(), placed.orderIds());
        String orderId = placed.singleOrderId();
        log.info("sandbox.probe.place.orderId={}", orderId);
        if (orderId == null) {
            log.info("sandbox.probe.CONCLUSION unexpected order_ids count {} (expected exactly 1, no slicing) — "
                    + "simulate-fills MUST stay TRUE", placed.orderIds());
            return;
        }

        // ── STEP 3: poll order status (does it fill?) ───────────────────────────────────────
        String lastStatus = "unknown";
        for (int i = 1; i <= 5; i++) {
            try {
                OrderStatusResponse st = orderClient.getOrderStatus(orderId);
                lastStatus = (st.data() != null) ? st.data().orderStatus() : "no-data";
                int filled = (st.data() != null) ? st.data().filledQuantity() : -1;
                log.info("sandbox.probe.status poll={} orderStatus={} filledQty={}", i, lastStatus, filled);
                if (st.isComplete() || st.isRejected() || st.isCancelled()) break;
            } catch (Exception e) {
                log.error("sandbox.probe.status.FAILED poll={} error={} "
                        + "(order-status read may not be supported in sandbox)", i, e.getMessage());
                break;
            }
            Thread.sleep(2000);
        }

        // ── STEP 4: by-tag query (does the reconcile path work in sandbox?) ─────────────────
        try {
            TaggedOrdersResponse book = orderClient.getOrderDetailsByTag(tag);
            log.info("sandbox.probe.by_tag apiStatus={} orderCount={}", book.status(), book.orders().size());
            book.orders().forEach(o -> log.info("sandbox.probe.by_tag.order corr={} orderId={} status={} filled={} tag={}",
                    o.correlationId(), o.orderId(), o.orderStatus(), o.filledQuantity(), o.tag()));
        } catch (Exception e) {
            log.error("sandbox.probe.by_tag.FAILED error={} (by-tag query may not be supported in sandbox)",
                    e.getMessage());
        }

        // ── STEP 5: cleanup ─────────────────────────────────────────────────────────────────
        try {
            orderClient.cancelOrder(orderId);
            log.info("sandbox.probe.cleanup.cancelled orderId={}", orderId);
        } catch (Exception e) {
            log.warn("sandbox.probe.cleanup.cancel.skipped orderId={} error={}", orderId, e.getMessage());
        }

        // ── Verdict hint ────────────────────────────────────────────────────────────────────
        boolean filled = "complete".equalsIgnoreCase(lastStatus);
        log.info("sandbox.probe.CONCLUSION lastStatus={} -> {}", lastStatus, filled
                ? "sandbox FILLS weekly NIFTY — simulate-fills can be turned OFF (real path works in sandbox)"
                : "sandbox did NOT fill within the poll window — keep simulate-fills TRUE unless it fills later");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────

    private record Contract(String instrumentKey, String expiry, int strike, String type, int lotSize) {}

    /** Fetches all NIFTY option contracts from production and returns a PUT at the nearest expiry. */
    @SuppressWarnings("unchecked")
    private Contract fetchNearestWeeklyPut() {
        String body;
        try {
            body = marketClient.get()
                    .uri("/v2/option/contract?instrument_key={k}", NIFTY_INDEX_KEY)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.error("sandbox.probe.contract.fetch.failed error={}", e.getMessage());
            return null;
        }

        List<Map<String, Object>> rows;
        try {
            Map<String, Object> map = json.readValue(body, Map.class);
            Object data = map.get("data");
            if (!(data instanceof List<?> list) || list.isEmpty()) {
                log.error("sandbox.probe.contract.empty body(first 300)={}",
                        body != null && body.length() > 300 ? body.substring(0, 300) : body);
                return null;
            }
            rows = (List<Map<String, Object>>) list;
        } catch (Exception e) {
            log.error("sandbox.probe.contract.parse.failed error={}", e.getMessage());
            return null;
        }

        LocalDate today = LocalDate.now();
        String nearest = null;
        for (Map<String, Object> row : rows) {
            Object exp = row.get("expiry");
            if (exp == null) continue;
            try {
                LocalDate d = LocalDate.parse(exp.toString());
                if (!d.isBefore(today) && (nearest == null || d.isBefore(LocalDate.parse(nearest)))) {
                    nearest = exp.toString();
                }
            } catch (Exception ignore) { /* skip unparseable */ }
        }
        if (nearest == null) {
            log.error("sandbox.probe.contract.no_future_expiry");
            return null;
        }

        for (Map<String, Object> row : rows) {
            if (nearest.equals(String.valueOf(row.get("expiry")))
                    && "PE".equalsIgnoreCase(String.valueOf(row.get("instrument_type")))) {
                log.info("sandbox.probe.contract.chosen raw={}", row);
                return new Contract(
                        String.valueOf(row.get("instrument_key")),
                        nearest,
                        asInt(row.get("strike_price"), 0),
                        "PE",
                        asInt(row.get("lot_size"), 75));
            }
        }
        log.error("sandbox.probe.contract.no_put_at_expiry expiry={}", nearest);
        return null;
    }

    private static int asInt(Object o, int fallback) {
        return (o instanceof Number n) ? n.intValue() : fallback;
    }

    /** Fetches the live LTP for an instrument from production (api.upstox.com), or null. */
    @SuppressWarnings("unchecked")
    private BigDecimal fetchLtp(String instrumentKey) {
        try {
            String body = marketClient.get()
                    .uri("/v2/market-quote/ltp?instrument_key={k}", instrumentKey)
                    .retrieve()
                    .body(String.class);
            Map<String, Object> map = json.readValue(body, Map.class);
            Object data = map.get("data");
            if (data instanceof Map<?, ?> m && !m.isEmpty()) {
                Object first = m.values().iterator().next();   // single instrument → one entry
                if (first instanceof Map<?, ?> quote) {
                    Object lp = quote.get("last_price");
                    if (lp instanceof Number n) return new BigDecimal(n.toString());
                }
            }
        } catch (Exception e) {
            log.warn("sandbox.probe.ltp.fetch.failed instrumentKey={} error={}", instrumentKey, e.getMessage());
        }
        return null;
    }

    /** Masked view — first 8 + last 8 chars — enough to eyeball without exposing the token. */
    private static String masked(String s) {
        if (s == null || s.isBlank()) return "none";
        if (s.length() <= 16) return "****";
        return s.substring(0, 8) + "…" + s.substring(s.length() - 8);
    }

    /** SHA-256 fingerprint (first 12 hex chars) — lets us compare token values without logging them. */
    private static String fingerprint(String s) {
        if (s == null || s.isBlank()) return "none";
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return "err";
        }
    }
}
