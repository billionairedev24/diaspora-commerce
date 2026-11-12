package com.afrotransact.identity.application;

import com.afrotransact.identity.domain.TourProgress.Status;

/** A write of one tour's progress (also one element of a merge). */
public record SaveTourProgressCommand(String tourId, int contentVersion, Status status, String lastStep) {
}
