package com.the3Cgrp.zupptrade.agent2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class Agent2Application {

    public static void main(String[] args) {
        SpringApplication.run(Agent2Application.class, args);
    }
}
