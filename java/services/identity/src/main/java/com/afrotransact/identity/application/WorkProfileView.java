package com.afrotransact.identity.application;

import java.time.Instant;

/** Back-office work profile: the job title, or null. */
public record WorkProfileView(String jobTitle, Instant updatedAt) {
}
