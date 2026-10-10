package com.afrotransact.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Payments service: Stripe intents, captures, refunds, Connect transfers and payouts, the double-entry ledger. */
@SpringBootApplication
public class PaymentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentsApplication.class, args);
    }
}
