package com.the3Cgrp.zupptrade.agent5.client;

import com.the3Cgrp.zupptrade.agent5.client.UpstoxOrderClient.UpstoxOrderException;
import com.the3Cgrp.zupptrade.agent5.client.request.PlaceOrderV3Request;
import com.the3Cgrp.zupptrade.agent5.client.response.TaggedOrdersResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies UpstoxOrderClient's retry policy and the v3 order endpoints.
 * MockRestServiceServer lets us count exactly how many HTTP requests each operation makes.
 */
class UpstoxOrderClientTest {

    private MockRestServiceServer server;
    private UpstoxOrderClient     orderClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        // Same bound client for all three roles; retryDelayMs = 0 so retry tests don't sleep.
        orderClient = new UpstoxOrderClient(client, client, client, 0L);
    }

    private PlaceOrderV3Request sampleOrder() {
        return PlaceOrderV3Request.limit("NSE_FO|63812", "BUY", "D", 65, new BigDecimal("1.00"), "ZUPP_TEST_L0");
    }

    @Test
    void placeOrder_hitsV3Endpoint_on5xx_doesNotRetry_exactlyOneRequest() {
        server.expect(times(1), requestTo(endsWith("/v3/order/place")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withServerError());

        assertThatThrownBy(() -> orderClient.placeOrder(sampleOrder()))
                .isInstanceOf(UpstoxOrderException.class);

        // Placement is not idempotent — a 5xx must NOT trigger a retry (would duplicate the order).
        server.verify();
    }

    @Test
    void placeOrder_on429_doesNotRetry_exactlyOneRequest() {
        server.expect(times(1), requestTo(endsWith("/v3/order/place")))
              .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> orderClient.placeOrder(sampleOrder()))
                .isInstanceOf(UpstoxOrderException.class);

        server.verify();
    }

    @Test
    void placeOrder_on5xx_throwsAmbiguous_but400ThrowsDeterministic() {
        // 5xx → ambiguous (outcome unknown)
        server.expect(times(1), requestTo(endsWith("/v3/order/place"))).andRespond(withServerError());
        try {
            orderClient.placeOrder(sampleOrder());
        } catch (UpstoxOrderException e) {
            assertThat(e.isAmbiguous()).isTrue();
        }
        server.verify();

        // 400 → deterministic (nothing placed)
        RestClient.Builder b2 = RestClient.builder();
        MockRestServiceServer s2 = MockRestServiceServer.bindTo(b2).build();
        RestClient c2 = b2.build();
        UpstoxOrderClient client2 = new UpstoxOrderClient(c2, c2, c2, 0L);
        s2.expect(times(1), requestTo(endsWith("/v3/order/place"))).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        try {
            client2.placeOrder(sampleOrder());
        } catch (UpstoxOrderException e) {
            assertThat(e.isAmbiguous()).isFalse();
        }
        s2.verify();
    }

    @Test
    void getOrderStatus_on5xx_retriesUpToThreeTimes() {
        server.expect(times(3), requestTo(containsString("/v2/order/details")))
              .andRespond(withServerError());

        assertThatThrownBy(() -> orderClient.getOrderStatus("ORD1"))
                .isInstanceOf(UpstoxOrderException.class);

        server.verify();
    }

    @Test
    void getOrderDetailsByTag_on429_isRetried_notThrownImmediately() {
        server.expect(times(3), requestTo(containsString("/v2/order/history")))
              .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> orderClient.getOrderDetailsByTag("ZUPP_ABCD_L0"))
                .isInstanceOf(UpstoxOrderException.class);

        server.verify();   // 429 retried, not thrown on the first hit
    }

    @Test
    void getOrderStatus_on400_throwsImmediately_noRetry() {
        server.expect(times(1), requestTo(containsString("/v2/order/details")))
              .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> orderClient.getOrderStatus("ORD1"))
                .isInstanceOf(UpstoxOrderException.class);

        server.verify();
    }

    @Test
    void getOrderDetailsByTag_parsesOrderList() {
        String json = """
                { "status":"success", "data":[
                  {"order_id":"O1","status":"open","transaction_type":"SELL",
                   "instrument_token":"NFO_OPT|NIFTY|2026-06-09|24500|PE",
                   "quantity":75,"filled_quantity":30,"pending_quantity":45,"average_price":50.5,"tag":"ZUPP_ABCD_L0"}
                ]}""";
        server.expect(requestTo(containsString("/v2/order/history?tag=ZUPP_ABCD_L0")))
              .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        TaggedOrdersResponse resp = orderClient.getOrderDetailsByTag("ZUPP_ABCD_L0");

        assertThat(resp.isApiSuccess()).isTrue();
        assertThat(resp.orders()).hasSize(1);
        assertThat(resp.orders().get(0).isOpen()).isTrue();
        assertThat(resp.orders().get(0).filledQuantity()).isEqualTo(30);
        server.verify();
    }
}
