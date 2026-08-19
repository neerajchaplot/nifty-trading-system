package com.the3Cgrp.zupptrade.agent3.client;

import com.the3Cgrp.zupptrade.agent3.client.Agent5FuturesClient.HandoffResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.http.HttpMethod.POST;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies the handoff outcome mapping (Fix B): a 2xx = REACHED (Agent 5 owns the plan), an HTTP
 * error = FAILED "rejected" (reached but refused — Agent 3 must fail the plan), and a transport
 * failure = FAILED "unreachable". The reached/rejected distinction is what stops a precondition
 * rejection from being reported to the user as "Agent 5 was unreachable".
 */
class Agent5FuturesClientTest {

    private final UUID planId = UUID.randomUUID();

    @Test
    void success_isReached() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(endsWith("/api/v1/agent5/futures/gtt")))
              .andExpect(method(POST))
              .andRespond(withSuccess(
                      "{\"gttOrderId\":\"GTT-1\",\"status\":\"FILLED\"}", MediaType.APPLICATION_JSON));

        HandoffResult result = new Agent5FuturesClient(builder.build()).placeGtt(planId);

        assertThat(result.reached()).isTrue();
        assertThat(result.failureReason()).isNull();
        server.verify();
    }

    @Test
    void httpError_isFailedAndRejected_notUnreachable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(endsWith("/api/v1/agent5/futures/gtt")))
              .andRespond(withServerError());

        HandoffResult result = new Agent5FuturesClient(builder.build()).placeGtt(planId);

        assertThat(result.reached()).isFalse();
        assertThat(result.failureReason()).contains("rejected").contains("500");
        server.verify();
    }

    @Test
    void transportError_isFailedAndUnreachable() {
        // A request factory that throws before any bytes leave — RestClient surfaces this as a
        // ResourceAccessException (not an HTTP status), i.e. the genuine "unreachable" branch.
        ClientHttpRequestFactory boom = (uri, method) -> { throw new IOException("Connection refused"); };
        RestClient rc = RestClient.builder().requestFactory(boom).baseUrl("http://localhost:1").build();

        HandoffResult result = new Agent5FuturesClient(rc).placeGtt(planId);

        assertThat(result.reached()).isFalse();
        assertThat(result.failureReason()).contains("unreachable");
    }
}
