package com.afrotransact.identity.application;

import java.util.List;

/**
 * Everything the tours need at sign-in: the person's progress and whether first-time tours may start by themselves
 * (platform flag {@code at.identity.tours.auto-start}).
 */
public record TourProgressSnapshot(List<TourProgressView> items, boolean autoStartEnabled) {
}
