package com.the3Cgrp.zupptrade.core.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT session config, shared by the minter (agent-user) and the validators (all agents). The
 * signing key comes from an env var only (never committed): {@code zupptrade.jwt.signing-key:
 * ${JWT_SIGNING_KEY}}. Registered by {@link IdentityAutoConfiguration}.
 */
@ConfigurationProperties(prefix = "zupptrade.jwt")
public class JwtProperties {

    /** HMAC signing secret — must be at least 32 bytes for HS256. Env-supplied. */
    private String signingKey;
    /** Access-token lifetime in minutes. */
    private long accessTtlMinutes = 30;
    /** Refresh-token lifetime in days. */
    private long refreshTtlDays = 7;
    /** Token issuer claim. */
    private String issuer = "zupptrade";

    public String getSigningKey()            { return signingKey; }
    public void setSigningKey(String v)      { this.signingKey = v; }
    public long getAccessTtlMinutes()        { return accessTtlMinutes; }
    public void setAccessTtlMinutes(long v)  { this.accessTtlMinutes = v; }
    public long getRefreshTtlDays()          { return refreshTtlDays; }
    public void setRefreshTtlDays(long v)    { this.refreshTtlDays = v; }
    public String getIssuer()                { return issuer; }
    public void setIssuer(String v)          { this.issuer = v; }
}
