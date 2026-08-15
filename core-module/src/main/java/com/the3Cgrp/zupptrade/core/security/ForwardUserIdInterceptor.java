package com.the3Cgrp.zupptrade.core.security;

import com.the3Cgrp.zupptrade.shared.constants.TradingConstants;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Forwards the acting user on trusted agent→agent calls: when a {@link UserContext} is populated
 * (UI-originated request thread), it adds {@code X-User-Id}. When no user is present (e.g. an
 * agent's scheduled thread), it adds nothing — the downstream call stays anonymous.
 *
 * <p>Attach to the internal RestClient builders that call other agents.
 */
public class ForwardUserIdInterceptor implements ClientHttpRequestInterceptor {

    private final UserContext userContext;

    public ForwardUserIdInterceptor(UserContext userContext) {
        this.userContext = userContext;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        userContext.current().ifPresent(u ->
                request.getHeaders().set(TradingConstants.USER_ID_HEADER, u.profileId().toString()));
        return execution.execute(request, body);
    }
}
