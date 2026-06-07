package com.the3Cgrp.zupptrade.agent2.db;

import com.the3Cgrp.zupptrade.agent2.domain.entity.UserProfileEntity;
import com.the3Cgrp.zupptrade.agent2.repository.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("local")
@Import(TestFlywayConfig.class)
class FlywayMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Test
    void flywayMigrations_run_allTablesExist() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name",
                String.class
        );

        assertThat(tables).containsExactlyInAnyOrder(
                "agent1_signals",
                "fii_dii_snapshots",
                "flyway_schema_history",
                "reference_data",
                "trade_executions",
                "trade_ledger",
                "trade_pnl",
                "trades",
                "user_profiles"
        );
    }

    @Test
    void v2Migration_seedUserProfile_exists() {
        assertThat(userProfileRepository.count()).isEqualTo(1);

        UserProfileEntity profile = userProfileRepository.findAll().stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("Seed user profile not found"));

        assertThat(profile.getUserId()).isEqualTo("default");
        assertThat(profile.getCapital()).isEqualByComparingTo("500000.00");
        assertThat(profile.getMinPop()).isEqualByComparingTo("80.00");
        assertThat(profile.getMaxPopPoppGap()).isEqualByComparingTo("15.00");
        assertThat(profile.getMaxLossPct()).isEqualByComparingTo("1.50");
        assertThat(profile.getSpreadWidthMin()).isEqualTo(50);
        assertThat(profile.getSpreadWidthMax()).isEqualTo(100);
    }

    @Test
    void referenceDataTable_hasCorrectColumns() {
        Map<String, Object> colInfo = jdbcTemplate.queryForMap(
                "SELECT column_name, data_type FROM information_schema.columns " +
                "WHERE table_schema = 'public' AND table_name = 'reference_data' AND column_name = 'value'"
        );
        assertThat(colInfo.get("data_type")).isEqualTo("jsonb");
    }

    @Test
    void v4Migration_tradesTable_tradeCodeAndAuditColumnsExist() {
        List<Map<String, Object>> cols = jdbcTemplate.queryForList(
                "SELECT column_name, data_type, is_nullable " +
                "FROM information_schema.columns " +
                "WHERE table_schema = 'public' AND table_name = 'trades' " +
                "  AND column_name IN ('trade_code', 'calculation_audit') " +
                "ORDER BY column_name"
        );

        assertThat(cols).hasSize(2);

        Map<String, Object> auditCol = cols.stream()
                .filter(c -> "calculation_audit".equals(c.get("column_name"))).findFirst().orElseThrow();
        assertThat(auditCol.get("data_type")).isEqualTo("jsonb");

        Map<String, Object> codeCol = cols.stream()
                .filter(c -> "trade_code".equals(c.get("column_name"))).findFirst().orElseThrow();
        assertThat(codeCol.get("data_type")).isEqualTo("character varying");
        assertThat(codeCol.get("is_nullable")).isEqualTo("NO");
    }

    @Test
    void v5Migration_tradeLedger_requiredColumnsExist() {
        List<String> cols = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns " +
                "WHERE table_schema = 'public' AND table_name = 'trade_ledger' ORDER BY column_name",
                String.class
        );
        assertThat(cols).contains("id", "trade_id", "event_type", "sequence_number",
                                  "occurred_by", "payload", "occurred_at");
    }

    @Test
    void v6Migration_tradeExecutions_requiredColumnsExist() {
        List<String> cols = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns " +
                "WHERE table_schema = 'public' AND table_name = 'trade_executions' ORDER BY column_name",
                String.class
        );
        assertThat(cols).contains("id", "trade_id", "execution_type", "broker_status",
                                  "requested_legs", "filled_legs", "broker_refs",
                                  "requested_lots", "filled_lots", "lot_size",
                                  "placed_at", "executed_at");
    }

    @Test
    void v7Migration_tradePnl_requiredColumnsAndUniqueConstraintExist() {
        List<String> cols = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns " +
                "WHERE table_schema = 'public' AND table_name = 'trade_pnl' ORDER BY column_name",
                String.class
        );
        assertThat(cols).contains("id", "trade_id", "snapshot_date",
                                  "realised_pnl", "unrealised_pnl", "total_pnl",
                                  "position_status", "synced_at", "raw_broker_payload");

        // Verify UNIQUE constraint exists on (trade_id, snapshot_date)
        List<String> constraints = jdbcTemplate.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints " +
                "WHERE table_schema = 'public' AND table_name = 'trade_pnl' " +
                "  AND constraint_type = 'UNIQUE'",
                String.class
        );
        assertThat(constraints).contains("uq_trade_pnl_trade_date");
    }

    @Test
    void tradesTable_jsonbColumnsExist() {
        List<String> jsonbCols = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns " +
                "WHERE table_schema = 'public' AND table_name = 'trades' AND data_type = 'jsonb' ORDER BY column_name",
                String.class
        );
        assertThat(jsonbCols).containsExactlyInAnyOrder(
                "calculation_audit", "entry_fills", "gate_results", "legs", "market_context", "monitor_config", "summary", "thresholds"
        );
    }
}
