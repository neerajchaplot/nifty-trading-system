# Test Data TODO

## S3.8 — Automate CLOSED trade error-path test

**File:** `curl/S3_agent3_silo.sh`

Currently S3.8 is commented out. It requires a manual SQL UPDATE before running
and a manual revert after:

```sql
UPDATE zupptrade_dev.trades SET status='CLOSED' WHERE id='a3000001-0000-0000-0000-000000000001';
-- (revert after test)
UPDATE zupptrade_dev.trades SET status='ACTIVE' WHERE id='a3000001-0000-0000-0000-000000000001';
```

**To automate:**
1. Add T-308 to `sql/04_seed_agent3_active_trades.sql` — BullPutSpread with `status='CLOSED'`,
   UUID `a3000001-0000-0000-0000-000000000008`, trade_code `T-20260627-0308`.
   Copy the monitor_config from T-301 (same spread, only status differs).
2. Add `T_A3_CLOSED="a3000001-0000-0000-0000-000000000008"` to `curl/vars.sh`.
3. Uncomment and update the curl in S3.8 to use `$T_A3_CLOSED`.

**Expected result:** 409 Conflict — `isEligibleForMonitoring(CLOSED)` returns false,
service throws `ResponseStatusException(HttpStatus.CONFLICT, ...)`.
