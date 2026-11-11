package com.afrotransact.bookings.application;

/** "Tell me when one joins": email alerts for places no provider serves yet (signed in or not). */
public interface ServiceAlertService {

    /**
     * Opens an alert for a ZIP (and optionally a category). Quiet about duplicates, so the endpoint reveals nothing
     * about who already subscribed.
     */
    void subscribe(String email, String zip, String category, String locale);

    /**
     * Called when a provider's area or services change: emails (once) every open alert the provider now serves,
     * via a {@code booking.service_alert} event.
     */
    void providerChanged(String orgId);
}
