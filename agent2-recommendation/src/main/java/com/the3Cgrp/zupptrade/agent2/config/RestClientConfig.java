package com.the3Cgrp.zupptrade.agent2.config;

import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxFuturesContractClient;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxHistoricalDataClient;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxMarketQuoteClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    /**
     * Reuse core-module's daily-candle client for the futures engine (prior-day OHLC +
     * compression ranges). core's UpstoxAutoConfiguration is excluded on Agent2Application,
     * so we wire this one bean explicitly onto agent2's own {@code upstoxRestClient}.
     */
    @Bean
    public UpstoxHistoricalDataClient upstoxHistoricalDataClient(RestClient upstoxRestClient) {
        return new UpstoxHistoricalDataClient(upstoxRestClient);
    }

    /** Current-month Nifty futures instrument_key resolution (Instrument Search API). */
    @Bean
    public UpstoxFuturesContractClient upstoxFuturesContractClient(RestClient upstoxRestClient) {
        return new UpstoxFuturesContractClient(upstoxRestClient);
    }

    /**
     * Market-quote LTP client — GIFT Nifty (pre-open session open) and Nifty spot (live reachability
     * level). core's UpstoxAutoConfiguration is excluded on Agent2Application, so wire it explicitly.
     */
    @Bean
    public UpstoxMarketQuoteClient upstoxMarketQuoteClient(RestClient upstoxRestClient) {
        return new UpstoxMarketQuoteClient(upstoxRestClient);
    }

    @Bean
    public RestClient upstoxRestClient(TradingConfig config, UpstoxTokenHolder tokenHolder) {
        TradingConfig.Upstox upstox = config.getUpstox();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(upstox.getConnectTimeoutSeconds()));
        factory.setReadTimeout(Duration.ofSeconds(upstox.getReadTimeoutSeconds()));

        return RestClient.builder()
                .baseUrl(upstox.getBaseUrl())
                .requestFactory(factory)
                // Interceptor reads token from UpstoxTokenHolder on every request.
                // UpstoxTokenDbLoader overwrites the holder at startup with the DB token,
                // so this picks up the fresh token without any RestClient restart.
                .requestInterceptor((request, body, execution) -> {
                    String token = tokenHolder.getToken();
                    if (token != null && !token.isBlank()) {
                        request.getHeaders().setBearerAuth(token);
                    }
                    request.getHeaders().set("Accept", "application/json");
                    // Market-quote LTP (v2/v3: VIX, Nifty spot, GIFT) and intraday-candle (v3) expect the
                    // Api-Version header — matches core's UpstoxAutoConfiguration client used by agent1/agent3.
                    request.getHeaders().set("Api-Version", "2.0");
                    return execution.execute(request, body);
                })
                .build();
    }
}
