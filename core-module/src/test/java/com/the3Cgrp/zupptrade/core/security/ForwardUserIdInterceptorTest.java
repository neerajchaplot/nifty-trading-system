package com.the3Cgrp.zupptrade.core.security;

import com.the3Cgrp.zupptrade.shared.constants.TradingConstants;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ForwardUserIdInterceptorTest {

    private static final String KEY = "test-internal-key-0123456789";

    private final UserContext ctx = new UserContext();
    private final ForwardUserIdInterceptor interceptor = new ForwardUserIdInterceptor(ctx, KEY);

    private HttpRequest requestWithHeaders(HttpHeaders headers) {
        HttpRequest req = mock(HttpRequest.class);
        when(req.getHeaders()).thenReturn(headers);
        return req;
    }

    private ClientHttpRequestExecution stubExecution() throws Exception {
        ClientHttpRequestExecution exec = mock(ClientHttpRequestExecution.class);
        when(exec.execute(any(), any())).thenReturn(mock(ClientHttpResponse.class));
        return exec;
    }

    @Test
    void sendsBothApiKeyAndUserId_whenUserPresent() throws Exception {
        UUID pid = UUID.randomUUID();
        ctx.set(new AuthenticatedUser(pid, "LIVE", true, "UPSTOX"));
        HttpHeaders headers = new HttpHeaders();

        interceptor.intercept(requestWithHeaders(headers), new byte[0], stubExecution());

        assertThat(headers.getFirst(TradingConstants.API_KEY_HEADER)).isEqualTo(KEY);
        assertThat(headers.getFirst(TradingConstants.USER_ID_HEADER)).isEqualTo(pid.toString());
    }

    /** Anonymous internal call (e.g. a scheduled thread with no bound user): key yes, user no. */
    @Test
    void sendsApiKeyButNoUserId_whenAnonymous() throws Exception {
        HttpHeaders headers = new HttpHeaders();

        interceptor.intercept(requestWithHeaders(headers), new byte[0], stubExecution());

        assertThat(headers.getFirst(TradingConstants.API_KEY_HEADER)).isEqualTo(KEY);
        assertThat(headers.getFirst(TradingConstants.USER_ID_HEADER)).isNull();
    }
}
