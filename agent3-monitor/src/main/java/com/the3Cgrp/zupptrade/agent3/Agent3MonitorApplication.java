package com.the3Cgrp.zupptrade.agent3;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// Strategy: ShedLock prevents duplicate cycle runs when a previous 5-min run is still in progress.
// defaultLockAtMostFor is the safety net — released even if the process dies mid-cycle.
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT4M30S")
public class Agent3MonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(Agent3MonitorApplication.class, args);
    }
}
