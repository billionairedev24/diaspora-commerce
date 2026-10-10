package com.afrotransact.identity.application;

import java.util.List;

/** Provision a staff (or driver) account with initial roles; the email is verified by the administrator. */
public record CreateStaffUserCommand(String firstName, String lastName, String email, String phone,
                                     String phoneRegion, String password, List<String> roles) {
}
