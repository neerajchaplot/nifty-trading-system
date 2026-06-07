package com.the3Cgrp.zupptrade.agent1;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableCaching
public class Agent1Application {

    public static void main(String[] args) {
        SpringApplication.run(Agent1Application.class, args);
    }
}
