package com.the3Cgrp.zupptrade.agent5.client;

import com.the3Cgrp.zupptrade.agent5.client.request.PlaceGttRequest;
import com.the3Cgrp.zupptrade.agent5.client.response.PlaceGttResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class UpstoxGttClientTest {

    private MockRestServiceServer server;
    private UpstoxGttClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new UpstoxGttClient(builder.build());
    }

    @Test
    void placeGtt_postsMultipleOcoPayload_andParsesGttOrderId() {
        server.expect(requestTo(endsWith("/v3/order/gtt/place")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(jsonPath("$.type").value("MULTIPLE"))
              .andExpect(jsonPath("$.product").value("I"))
              .andExpect(jsonPath("$.transaction_type").value("BUY"))
              .andExpect(jsonPath("$.quantity").value(65))
              .andExpect(jsonPath("$.tag").value("ZUPP_A1B2C3D4"))
              .andExpect(jsonPath("$.rules[0].strategy").value("ENTRY"))
              .andExpect(jsonPath("$.rules[0].trigger_type").value("IMMEDIATE"))
              .andExpect(jsonPath("$.rules[1].strategy").value("TARGET"))
              .andExpect(jsonPath("$.rules[2].strategy").value("STOPLOSS"))
              .andRespond(withSuccess(
                      "{\"status\":\"success\",\"data\":{\"gtt_order_ids\":[\"GTT-777\"]}}",
                      MediaType.APPLICATION_JSON));

        PlaceGttRequest req = PlaceGttRequest.oco("NSE_FO|63812", "BUY", 65,
                new BigDecimal("24280"), new BigDecimal("24357"), new BigDecimal("24237"), "ZUPP_A1B2C3D4");
        PlaceGttResponse res = client.placeGtt(req);

        assertThat(res.isApiSuccess()).isTrue();
        assertThat(res.gttOrderId()).isEqualTo("GTT-777");
        server.verify();
    }
}
