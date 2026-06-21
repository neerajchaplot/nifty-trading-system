package com.the3Cgrp.zupptrade.core.upstox.client;

import com.the3Cgrp.zupptrade.core.upstox.model.UpstoxApiResponse;
import com.the3Cgrp.zupptrade.core.upstox.model.position.UpstoxPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * GET /v2/portfolio/positions — fetches current open positions.
 *
 * Used by Agent 3 at the start of each monitoring cycle to detect positions that
 * were closed externally (e.g. user manually exited on the Upstox mobile app).
 *
 * Returns empty map on any error — callers must treat an empty result as
 * "data unavailable, skip reconciliation" NOT "all positions are flat".
 */
public class UpstoxPositionClient {

    private static final Logger log = LoggerFactory.getLogger(UpstoxPositionClient.class);

    private final RestClient upstoxRestClient;

    public UpstoxPositionClient(RestClient upstoxRestClient) {
        this.upstoxRestClient = upstoxRestClient;
    }

    /**
     * Returns a map of instrument key → net quantity for all current positions.
     *
     * Keys are normalised to use '|' as separator (matching our order/chain format),
     * so callers can compare directly against instrumentKey from TradeLegDto.
     *
     * Returns empty map on API error or market-closed response — callers must not
     * interpret an empty result as "all positions flat".
     */
    public Map<String, Integer> fetchNetQuantities() {
        try {
            UpstoxApiResponse<List<UpstoxPosition>> response = upstoxRestClient.get()
                    .uri("/v2/portfolio/positions")
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (response == null || !response.isSuccess() || response.data() == null) {
                log.warn("upstox.positions.unavailable status={}",
                        response != null ? response.status() : "null");
                return Collections.emptyMap();
            }

            return response.data().stream()
                    .filter(p -> p.instrumentToken() != null)
                    .collect(Collectors.toMap(
                            p -> p.instrumentToken().replace(":", "|"),
                            UpstoxPosition::quantity,
                            (a, b) -> a  // keep first on duplicate key
                    ));

        } catch (HttpClientErrorException.Unauthorized e) {
            log.error("upstox.positions.token.expired — access token invalid. Skipping reconciliation.");
            return Collections.emptyMap();
        } catch (Exception e) {
            log.warn("upstox.positions.error error={} — skipping reconciliation", e.getMessage());
            return Collections.emptyMap();
        }
    }
}
