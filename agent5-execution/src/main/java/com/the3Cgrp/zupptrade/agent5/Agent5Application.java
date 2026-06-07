package com.the3Cgrp.zupptrade.agent5;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Agent 5 — Trade Execution.
 *
 * Upstox connectivity (token, RestClient beans) is provided automatically
 * by core-module's UpstoxAutoConfiguration — no local config needed.
 *
 * Agent 5 only owns: Agent5ExecutionProperties (agent5.execution.*)
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class Agent5Application {
    public static void main(String[] args) {
        SpringApplication.run(Agent5Application.class, args);
    }
}
