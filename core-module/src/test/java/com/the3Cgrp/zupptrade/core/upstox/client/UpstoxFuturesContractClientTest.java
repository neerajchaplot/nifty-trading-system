package com.the3Cgrp.zupptrade.core.upstox.client;

import com.the3Cgrp.zupptrade.core.upstox.model.contract.UpstoxFuturesContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class UpstoxFuturesContractClientTest {

    private MockRestServiceServer server;
    private UpstoxFuturesContractClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new UpstoxFuturesContractClient(builder.build());
    }

    @Test
    void fetch_hitsSearchEndpoint_parsesFutContracts() {
        server.expect(requestTo(containsString("/v2/instruments/search")))
              .andExpect(requestTo(containsString("instrument_types=FUT")))
              .andExpect(requestTo(containsString("query=NIFTY")))
              .andExpect(method(HttpMethod.GET))
              .andRespond(withSuccess("""
                  {"status":"success","data":[
                    {"instrument_key":"NSE_FO|35079","expiry":"2026-08-27","trading_symbol":"NIFTY 27 AUG FUT","underlying_symbol":"NIFTY","instrument_type":"FUT"},
                    {"instrument_key":"NSE_FO|35080","expiry":"2026-09-24","trading_symbol":"NIFTY 24 SEP FUT","underlying_symbol":"NIFTY","instrument_type":"FUT"},
                    {"instrument_key":"NSE_FO|44444","expiry":"2026-08-27","trading_symbol":"BANKNIFTY 27 AUG FUT","underlying_symbol":"BANKNIFTY","instrument_type":"FUT"}
                  ]}""", MediaType.APPLICATION_JSON));

        List<UpstoxFuturesContract> contracts = client.fetchNiftyFutures();

        assertThat(contracts).hasSize(3);
        assertThat(contracts.get(0).instrumentKey()).isEqualTo("NSE_FO|35079");
        assertThat(contracts.get(0).underlyingSymbol()).isEqualTo("NIFTY");
        assertThat(contracts.get(0).expiry().toString()).isEqualTo("2026-08-27");
        server.verify();
    }

    @Test
    void fetch_onError_returnsEmptyList() {
        server.expect(requestTo(containsString("/v2/instruments/search")))
              .andRespond(withServerError());

        assertThat(client.fetchNiftyFutures()).isEmpty();
        server.verify();
    }
}
