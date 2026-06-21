-- api_tokens: stores AES-256-GCM encrypted access tokens for external APIs.
-- Created by upstox-auth module. Read (but never written) by the trading system.
--
-- The encrypted_token column format: Base64(IV[12] || Ciphertext || GCM-AuthTag[16])
-- Decryption requires TOKEN_ENCRYPTION_KEY environment variable — never stored in DB.
--
-- Uses V100 to avoid conflicting with trading system migrations (V1–V99).

CREATE TABLE IF NOT EXISTS api_tokens (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    service         VARCHAR(50) NOT NULL UNIQUE,
    encrypted_token TEXT        NOT NULL,
    fetched_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  api_tokens                IS 'Encrypted API access tokens. Raw tokens never stored.';
COMMENT ON COLUMN api_tokens.service        IS 'Service identifier, e.g. UPSTOX';
COMMENT ON COLUMN api_tokens.encrypted_token IS 'AES-256-GCM: Base64(IV[12] || Ciphertext || AuthTag[16])';
COMMENT ON COLUMN api_tokens.fetched_at     IS 'When this token was obtained from the provider';
