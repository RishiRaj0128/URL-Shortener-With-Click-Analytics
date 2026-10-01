package com.urlshortener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Profile;

/**
 * Dedicated entry point for running the Click Stream Consumer as an independent worker process,
 * completely decoupled from the Web / Redirect JVM instance.
 */
@SpringBootApplication
@Profile("consumer")
public class ConsumerApplication {

    private static final Logger log = LoggerFactory.getLogger(ConsumerApplication.class);

    public static void main(String[] args) {
        System.setProperty("spring.profiles.active", "consumer");
        System.setProperty("spring.main.web-application-type", "none");
        log.info("Starting standalone Redis Stream Consumer worker process...");
        SpringApplication.run(ConsumerApplication.class, args);
    }
}
