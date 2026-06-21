package com.the3Cgrp.zupptrade.agent3.client;

import com.the3Cgrp.zupptrade.shared.dto.TradeLegDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeCardDto;
import com.the3Cgrp.zupptrade.shared.enums.LegAction;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import com.the3Cgrp.zupptrade.shared.enums.Strategy;
import com.the3Cgrp.zupptrade.shared.enums.SpreadDirection;
import com.the3Cgrp.zupptrade.shared.enums.TradeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class Agent5ExecuteClientTest {

    private Agent5ExecuteClient client;
    private MockRestServiceServer mockServer;

    private static final UUID TRADE_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://agent5-test");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new Agent5ExecuteClient(builder.build());
    }

    @Test
    void execute_activeStatus_returnsTrue() {
        mockServer.expect(requestTo("http://agent5-test/api/v1/agent5/execute"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.tradeId").value(TRADE_ID.toString()))
                .andExpect(jsonPath("$.legs").isArray())
                .andExpect(jsonPath("$.legs.length()").value(2))
                .andExpect(jsonPath("$.legs[0].action").value("SELL"))   // short leg first
                .andExpect(jsonPath("$.legs[1].action").value("BUY"))    // long leg second
                .andExpect(jsonPath("$.legs[0].instrumentKey").value("NFO_OPT|NIFTY|2026-06-17|23500|PE"))
                .andExpect(jsonPath("$.legs[0].quantity").value(390))    // 6 lots × 65
                .andRespond(withSuccess(executeResponseJson("ACTIVE", false, null), MediaType.APPLICATION_JSON));

        boolean result = client.execute(sampleTradeCard());

        assertThat(result).isTrue();
        mockServer.verify();
    }

    @Test
    void execute_rejectedStatus_returnsFalse() {
        mockServer.expect(requestTo("http://agent5-test/api/v1/agent5/execute"))
                .andRespond(withSuccess(executeResponseJson("REJECTED", false, "Insufficient margin"),
                        MediaType.APPLICATION_JSON));

        boolean result = client.execute(sampleTradeCard());

        assertThat(result).isFalse();
    }

    @Test
    void execute_httpError500_returnsFalse() {
        mockServer.expect(requestTo("http://agent5-test/api/v1/agent5/execute"))
                .andRespond(withServerError());

        boolean result = client.execute(sampleTradeCard());

        assertThat(result).isFalse();
    }

    @Test
    void execute_http400BadRequest_returnsFalse() {
        mockServer.expect(requestTo("http://agent5-test/api/v1/agent5/execute"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("{\"detail\":\"Missing instrument key\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        boolean result = client.execute(sampleTradeCard());

        assertThat(result).isFalse();
    }

    @Test
    void execute_activeWithSlippageAlert_returnsTrue() {
        // Slippage alert is a warning — trade is still ACTIVE, should still return true
        mockServer.expect(requestTo("http://agent5-test/api/v1/agent5/execute"))
                .andRespond(withSuccess(
                        executeResponseJson("ACTIVE", true, "Slippage exceeded 10%"),
                        MediaType.APPLICATION_JSON));

        boolean result = client.execute(sampleTradeCard());

        assertThat(result).isTrue(); // trade is live despite slippage
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private TradeCardDto sampleTradeCard() {
        TradeLegDto shortLeg = new TradeLegDto(OptionType.PE, 23500,
                new BigDecimal("45.50"), LegAction.SELL, new BigDecimal("-0.18"),
                new BigDecimal("0.82"), "NFO_OPT|NIFTY|2026-06-17|23500|PE");
        TradeLegDto longLeg = new TradeLegDto(OptionType.PE, 23400,
                new BigDecimal("28.20"), LegAction.BUY, new BigDecimal("-0.14"),
                new BigDecimal("0.88"), "NFO_OPT|NIFTY|2026-06-17|23400|PE");

        return new TradeCardDto(
                TRADE_ID, Strategy.BULL_PUT_SPREAD, SpreadDirection.CREDIT,
                LocalDate.of(2026, 6, 17), 3,
                shortLeg, longLeg,
                new BigDecimal("17.30"), 6, 65,
                new BigDecimal("6747.00"), new BigDecimal("39000.00"), new BigDecimal("19500.00"),
                new BigDecimal("82.00"), new BigDecimal("88.00"), new BigDecimal("6.00"),
                new BigDecimal("1.07"), new BigDecimal("130.00"), new BigDecimal("-0.04"),
                null, null, "Bull put spread — bearish setup, credit 17.30",
                null, null, TradeStatus.CONFIRMED
        );
    }

    private String executeResponseJson(String status, boolean slippageAlert, String rejectionReason) {
        String rejection = rejectionReason != null ? "\"" + rejectionReason + "\"" : "null";
        return """
                {
                  "tradeId": "%s",
                  "executionStatus": "%s",
                  "slippageAlert": %s,
                  "rejectionReason": %s
                }
                """.formatted(TRADE_ID, status, slippageAlert, rejection);
    }
}
