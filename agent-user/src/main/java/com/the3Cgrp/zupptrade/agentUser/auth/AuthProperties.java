package com.the3Cgrp.zupptrade.agentUser.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * OAuth/OIDC provider config for the two login paths. Secrets are env-only.
 * Upstox client-id/secret come from {@code upstox.api.api-key/api-secret} (UpstoxProperties);
 * only the login redirect URI lives here.
 */
@ConfigurationProperties(prefix = "zupptrade.auth")
public class AuthProperties {

    /** Where the SPA should land after a successful login (tokens delivered in the URL fragment). */
    private String uiRedirectUri = "http://localhost:4200/auth/callback";

    /** Where a MOBILE app should land after login — a custom-scheme deep link the OS routes back into the app. */
    private String mobileRedirectUri = "zupptrade://auth/callback";

    @NestedConfigurationProperty
    private Google google = new Google();
    @NestedConfigurationProperty
    private Upstox upstox = new Upstox();

    public String getUiRedirectUri()        { return uiRedirectUri; }
    public void setUiRedirectUri(String v)  { this.uiRedirectUri = v; }
    public String getMobileRedirectUri()       { return mobileRedirectUri; }
    public void setMobileRedirectUri(String v) { this.mobileRedirectUri = v; }
    public Google getGoogle()               { return google; }
    public void setGoogle(Google v)         { this.google = v; }
    public Upstox getUpstox()               { return upstox; }
    public void setUpstox(Upstox v)         { this.upstox = v; }

    public static class Google {
        private String clientId;
        private String clientSecret;
        private String redirectUri;
        public String getClientId()             { return clientId; }
        public void setClientId(String v)       { this.clientId = v; }
        public String getClientSecret()         { return clientSecret; }
        public void setClientSecret(String v)   { this.clientSecret = v; }
        public String getRedirectUri()          { return redirectUri; }
        public void setRedirectUri(String v)    { this.redirectUri = v; }
    }

    public static class Upstox {
        /** agent-user callback registered in the Upstox developer portal. */
        private String redirectUri;
        public String getRedirectUri()          { return redirectUri; }
        public void setRedirectUri(String v)    { this.redirectUri = v; }
    }
}
