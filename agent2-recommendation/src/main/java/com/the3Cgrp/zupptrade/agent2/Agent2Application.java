package com.the3Cgrp.zupptrade.agent2;

import com.the3Cgrp.zupptrade.core.alert.AlertAutoConfiguration;
import com.the3Cgrp.zupptrade.core.expiry.ExpiryDateAutoConfiguration;
import com.the3Cgrp.zupptrade.core.upstox.config.UpstoxAutoConfiguration;
import com.the3Cgrp.zupptrade.core.web.WebExceptionHandlerAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Agent 2 depends on core-module only for the pure explain package (ExplanationBuilder /
 * MarketVocab). core-module's four auto-configurations are excluded here so pulling in that jar
 * cannot register Upstox/alert/expiry/exception beans — agent2 keeps its own Upstox clients,
 * exception handling and datasource untouched.
 */
@SpringBootApplication(exclude = {
        UpstoxAutoConfiguration.class,
        AlertAutoConfiguration.class,
        WebExceptionHandlerAutoConfiguration.class,
        ExpiryDateAutoConfiguration.class
})
@ConfigurationPropertiesScan
public class Agent2Application {

    public static void main(String[] args) {
        SpringApplication.run(Agent2Application.class, args);
    }
}
