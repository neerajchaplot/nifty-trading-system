package com.the3Cgrp.zupptrade.core.security;

import com.the3Cgrp.zupptrade.shared.constants.TradingConstants;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Authenticates and attributes trusted agent→agent calls:
 * <ul>
 *   <li>Always sends {@code X-API-Key} — proves the caller is a trusted internal service, which is
 *       what makes the downstream {@link UserIdentityFilter} accept the forwarded identity.</li>
 *   <li>Sends {@code X-User-Id} when a {@link UserContext} is populated (UI-originated thread, or a
 *       scheduled action bound to the trade owner). When no user is present, nothing is added and
 *       the downstream call stays anonymous.</li>
 * </ul>
 *
 * <p>Attach ONLY to internal RestClient builders that call other agents — never to clients that call
 * external providers (Upstox/Marketaux/Groq), or the internal key would leak to third parties.
 */
public class ForwardUserIdInterceptor implements ClientHttpRequestInterceptor {

    private final UserContext userContext;
    private final String internalApiKey;

    public ForwardUserIdInterceptor(UserContext userContext, String internalApiKey) {
        this.userContext = userContext;
        this.internalApiKey = internalApiKey;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        if (internalApiKey != null && !internalApiKey.isBlank()) {
            request.getHeaders().set(TradingConstants.API_KEY_HEADER, internalApiKey);
        }
        userContext.current().ifPresent(u ->
                request.getHeaders().set(TradingConstants.USER_ID_HEADER, u.profileId().toString()));
        return execution.execute(request, body);
    }
}
