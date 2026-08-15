package com.the3Cgrp.zupptrade.core.upstox.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenEncryptionServiceTest {

    // Base64 of "0123456789abcdef0123456789abcdef" (exactly 32 bytes → 256-bit AES key).
    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void encryptThenDecrypt_roundTrips() {
        TokenEncryptionService svc = new TokenEncryptionService(KEY);
        String token = "upstox-access-token-abc123";

        String encrypted = svc.encrypt(token);

        assertThat(encrypted).isNotBlank().isNotEqualTo(token);
        assertThat(svc.decrypt(encrypted)).isEqualTo(token);
    }

    @Test
    void encrypt_usesFreshIv_soCiphertextDiffersEachCall() {
        TokenEncryptionService svc = new TokenEncryptionService(KEY);
        String token = "same-plaintext";

        // Different IV each call → different ciphertext, but both decrypt back to the same value.
        String a = svc.encrypt(token);
        String b = svc.encrypt(token);
        assertThat(a).isNotEqualTo(b);
        assertThat(svc.decrypt(a)).isEqualTo(token);
        assertThat(svc.decrypt(b)).isEqualTo(token);
    }
}
