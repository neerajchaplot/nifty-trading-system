-- V105: Fix lot size reference data seeded incorrectly by V101.
--
-- V101 used key 'NIFTY_LOT_SIZE' and plain JSON '65'.
-- RecommendationService.fetchLotSize() expects key 'nifty.lot.size' and '{"lotSize": 65}'.
-- This migration adds the correct row and removes the old one.

INSERT INTO reference_data (key, value, source, ttl_hours)
VALUES ('nifty.lot.size', '{"lotSize": 65}'::jsonb, 'flyway-seed', 8760)
ON CONFLICT (key) DO UPDATE
    SET value     = EXCLUDED.value,
        source    = EXCLUDED.source,
        ttl_hours = EXCLUDED.ttl_hours;

DELETE FROM reference_data WHERE key = 'NIFTY_LOT_SIZE';
