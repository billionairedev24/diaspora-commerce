package com.afrotransact.identity.application;

import java.time.Instant;

import com.afrotransact.identity.domain.TourProgress.Status;

/** One tour's progress as the person sees it. */
public record TourProgressView(String tourId, int contentVersion, Status status, String lastStep, Instant updatedAt) {
}
