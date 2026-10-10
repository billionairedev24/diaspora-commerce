package com.afrotransact.trustops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Trust & safety ops: moderation queue, disputes / A-to-z claims, immutable audit log, seller health, support cases. */
@SpringBootApplication
public class TrustOpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrustOpsApplication.class, args);
    }
}
