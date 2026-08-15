package com.the3Cgrp.zupptrade.core.upstox.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM for the tokens stored in api_tokens.
 *
 * Historically decrypt-only (the trading system only read tokens). {@link #encrypt(String)} was
 * added for the multi-user auth module (Phase 2), which stores each user's Upstox token on login.
 *
 * The TOKEN_ENCRYPTION_KEY env var must match across all writers/readers.
 * Encrypted format: Base64(IV[12] || Ciphertext || GCM-AuthTag[16])
 */
public class TokenEncryptionService {

    private static final String ALGORITHM  = "AES/GCM/NoPadding";
    private static final int    IV_LENGTH  = 12;
    private static final int    TAG_LENGTH = 128;

    private final SecretKeySpec keySpec;
    private final SecureRandom random = new SecureRandom();

    public TokenEncryptionService(String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                "TOKEN_ENCRYPTION_KEY must be 32 bytes (256-bit) base64-encoded");
        }
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Encrypts a plaintext token into the stored format: Base64(IV[12] || Ciphertext || AuthTag).
     * A fresh random IV is generated per call (never reuse an IV under GCM).
     */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Token encryption failed", e);
        }
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
