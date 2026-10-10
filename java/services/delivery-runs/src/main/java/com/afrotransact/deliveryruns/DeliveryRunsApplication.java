package com.afrotransact.deliveryruns;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Delivery-runs service: community delivery runs to towns outside regular delivery areas (locations, runs with
 * cutoff, minimum and capacity, run slots for preorders, subscriptions, announcements and run economics).
 */
@SpringBootApplication
public class DeliveryRunsApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeliveryRunsApplication.class, args);
    }
}
