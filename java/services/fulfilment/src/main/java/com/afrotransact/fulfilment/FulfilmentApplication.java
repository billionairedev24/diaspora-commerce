package com.afrotransact.fulfilment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Fulfilment service: FCs, pick/pack, dispatch, driver routes, offline-first driver sync and proof of delivery. */
@SpringBootApplication
public class FulfilmentApplication {

    public static void main(String[] args) {
        SpringApplication.run(FulfilmentApplication.class, args);
    }
}
