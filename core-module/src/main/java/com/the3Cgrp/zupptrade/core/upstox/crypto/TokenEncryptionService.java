package com.the3Cgrp.zupptrade.core.upstox.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM decryption for tokens written by the upstox-auth module.
 *
 * Intentionally decrypt-only in the trading system — the trading system
 * never encrypts tokens, it only reads them from the database.
 *
 * The TOKEN_ENCRYPTION_KEY env var must match the one used by upstox-auth.
 * Encrypted format: Base64(IV[12] || Ciphertext || GCM-AuthTag[16])
 */
public class TokenEncryptionService {

    private static final String ALGORITHM  = "AES/GCM/NoPadding";
    private static final int    IV_LENGTH  = 12;
    private static final int    TAG_LENGTH = 128;

    private final SecretKeySpec keySpec;

    public TokenEncryptionService(String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                "TOKEN_ENCRYPTION_KEY must be 32 bytes (256-bit) base64-encoded");
        }
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
    }

    public String decrypt(String encryptedBase64) {
        try {
            byte[] combined   = Base64.getDecoder().decode(encryptedBase64);
            byte[] iv         = Arrays.copyOfRange(combined, 0, IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(combined, IV_LENGTH, combined.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Token decryption failed — wrong key or tampered data", e);
        }
    }
}
