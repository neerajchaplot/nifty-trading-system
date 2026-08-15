package com.the3Cgrp.zupptrade.core.security;

/** A freshly minted access + refresh token pair returned to the client after login/refresh. */
public record TokenPair(String accessToken, String refreshToken, long accessExpiresInSeconds) {}
