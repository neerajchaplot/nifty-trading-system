package com.the3Cgrp.zupptrade.agent4.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.the3Cgrp.zupptrade.agent4.mapper.TradeAuditMapper;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "agent4.analytics")
public class AnalyticsConfig {

    private int defaultPageSize = 5;
    private int maxDateRangeDays = 365;

    @Bean
    public TradeAuditMapper tradeAuditMapper() {
        // Spring Boot 4 uses Jackson 3 (tools.jackson) as its auto-configured bean.
        // TradeAuditMapper only needs plain readValue for JSONB text — a vanilla
        // Jackson 2 ObjectMapper (still on classpath transitively) is sufficient here.
        return new TradeAuditMapper(new ObjectMapper());
    }

    public int getDefaultPageSize()  { return defaultPageSize; }
    public void setDefaultPageSize(int v) { this.defaultPageSize = v; }
    public int getMaxDateRangeDays() { return maxDateRangeDays; }
    public void setMaxDateRangeDays(int v) { this.maxDateRangeDays = v; }
}
