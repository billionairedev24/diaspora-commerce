package com.afrotransact.bookings.application;

import com.afrotransact.bookings.domain.ServiceOffering;
import com.afrotransact.bookings.domain.TakeHome;

/** A service with what the provider takes home at its listed price. */
public record ServiceView(ServiceOffering offering, TakeHome takeHome) {
}
