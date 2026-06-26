-- spread_direction is not applicable for NO_TRADE / SKIP outcomes where the engine
-- exits before strategy selection completes. These are persisted as REJECTED records
-- with no spread direction determined.
ALTER TABLE zupptrade_dev.trades
    ALTER COLUMN spread_direction DROP NOT NULL;
