-- ============================================================
-- V1 — Initial schema for Nifty Trading System
-- ============================================================

CREATE TABLE agent1_signals (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    timestamp             TIMESTAMPTZ  NOT NULL,
    expiry_date           DATE         NOT NULL,
    bias                  VARCHAR(10)  NOT NULL,
    strength              VARCHAR(10)  NOT NULL,
    composite_score       DECIMAL(6,4) NOT NULL,
    confidence            DECIMAL(4,2) NOT NULL,
    confidence_label      VARCHAR(10)  NOT NULL,
    vix_level             DECIMAL(6,2),
    vix_regime            VARCHAR(10),
    vix_direction         VARCHAR(10),
    score_breakdown       JSONB,
    commentary_divergence BOOLEAN      NOT NULL DEFAULT FALSE,
    key_levels            JSONB,
    raw_inputs            JSONB,
    status                VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE user_profiles (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             VARCHAR(50)   UNIQUE NOT NULL,
    capital             DECIMAL(15,2) NOT NULL,
    min_pop             DECIMAL(4,2)  NOT NULL DEFAULT 0.80,
    max_pop_popp_gap    DECIMAL(4,2)  NOT NULL DEFAULT 15.0,
    max_loss_pct        DECIMAL(4,2)  NOT NULL DEFAULT 1.50,
    spread_width_min    INT           NOT NULL DEFAULT 50,
    spread_width_max    INT           NOT NULL DEFAULT 100,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE TABLE trades (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    agent1_signal_id    UUID         REFERENCES agent1_signals(id),
    user_profile_id     UUID         NOT NULL REFERENCES user_profiles(id),
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING_CONFIRM',
    strategy            VARCHAR(30)  NOT NULL,
    spread_direction    VARCHAR(10)  NOT NULL,
    expiry_date         DATE         NOT NULL,
    dte                 INT          NOT NULL,
    legs                JSONB        NOT NULL,
    summary             JSONB        NOT NULL,
    market_context      JSONB,
    thresholds          JSONB        NOT NULL,
    monitor_config      JSONB,
    gate_results        JSONB        NOT NULL,
    entry_fills         JSONB,
    generated_at        TIMESTAMPTZ  NOT NULL,
    valid_until         TIMESTAMPTZ  NOT NULL,
    confirmed_at        TIMESTAMPTZ,
    closed_at           TIMESTAMPTZ,
    close_reason        VARCHAR(100),
    actual_pnl          DECIMAL(12,2),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE reference_data (
    key         VARCHAR(100) PRIMARY KEY,
    value       JSONB        NOT NULL,
    source      VARCHAR(200),
    fetched_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    ttl_hours   INT          NOT NULL DEFAULT 24
);

CREATE INDEX idx_agent1_signals_expiry  ON agent1_signals(expiry_date);
CREATE INDEX idx_agent1_signals_status  ON agent1_signals(status);
CREATE INDEX idx_trades_status          ON trades(status);
CREATE INDEX idx_trades_expiry_date     ON trades(expiry_date);
CREATE INDEX idx_trades_signal          ON trades(agent1_signal_id);
CREATE INDEX idx_trades_user_profile    ON trades(user_profile_id);
