package com.afrotransact.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Identity service: Spring Authorization Server (OAuth 2.1 / OIDC), accounts, RBAC, MFA, passkeys, social login. */
@SpringBootApplication
public class IdentityApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityApplication.class, args);
    }
}
