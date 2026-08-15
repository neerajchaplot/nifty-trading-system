package com.the3Cgrp.zupptrade.agent4;

import com.the3Cgrp.zupptrade.core.alert.AlertAutoConfiguration;
import com.the3Cgrp.zupptrade.core.expiry.ExpiryDateAutoConfiguration;
import com.the3Cgrp.zupptrade.core.upstox.config.UpstoxAutoConfiguration;
import com.the3Cgrp.zupptrade.core.web.WebExceptionHandlerAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Agent 4 depends on core-module only for the identity/security beans (UserContext,
 * UserIdentityFilter, OwnershipGuard) that power per-user read scoping (multi-user Phase 5).
 * core-module's other four auto-configurations are excluded here so pulling in that jar cannot
 * register Upstox/alert/expiry/exception beans — agent4 keeps its own exception handling,
 * critical-alert service and datasource untouched. IdentityAutoConfiguration stays active.
 */
@SpringBootApplication(exclude = {
        UpstoxAutoConfiguration.class,
        AlertAutoConfiguration.class,
        WebExceptionHandlerAutoConfiguration.class,
        ExpiryDateAutoConfiguration.class
})
public class Agent4Application {
    public static void main(String[] args) {
        SpringApplication.run(Agent4Application.class, args);
    }
}
