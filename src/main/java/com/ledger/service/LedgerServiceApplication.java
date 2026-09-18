package com.ledger.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling activates OutboxRelay's @Scheduled poller (Phase 2 of
// v1 -> v1.1). Placed on the application root rather than a dedicated
// config class since it is the only scheduled work in this service so far -
// promote it to its own @Configuration class if/when a second one appears.
@SpringBootApplication
@EnableScheduling
public class LedgerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerServiceApplication.class, args);
    }
}
