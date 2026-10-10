package com.afrotransact.retail;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Retail service: AfroTransact Retail (1P) purchasing — product master, suppliers, purchase orders, inbound
 * shipments/ASN, goods receipt, landed cost and GL, three-way match.
 */
@SpringBootApplication
public class RetailApplication {

    public static void main(String[] args) {
        SpringApplication.run(RetailApplication.class, args);
    }
}
