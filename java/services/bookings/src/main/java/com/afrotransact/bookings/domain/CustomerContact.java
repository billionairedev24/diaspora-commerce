package com.afrotransact.bookings.domain;

/** How the provider and notifications reach the customer. */
public record CustomerContact(String name, String email, String phone) {

    public CustomerContact {
        if (name == null || name.isBlank()) {
            throw new BookingsRuleViolation("bookings.invalid-customer", "Customer name is required");
        }
        name = name.trim();
        email = email == null || email.isBlank() ? null : email.trim();
        phone = phone == null || phone.isBlank() ? null : phone.trim();
    }

    public String firstName() {
        int space = name.indexOf(' ');
        return space < 0 ? name : name.substring(0, space);
    }
}
