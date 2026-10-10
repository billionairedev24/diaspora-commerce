package com.afrotransact.seller.application;

/** Result of starting onboarding: the application and whether it was created now (false = resumed). */
public record StartResult(ApplicationView application, boolean created) {
}
