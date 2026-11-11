package com.afrotransact.bookings.application;

import com.afrotransact.bookings.domain.Provider;
import com.afrotransact.bookings.domain.ServiceOffering;
import com.afrotransact.bookings.port.ServiceAlertRepository.Alert;

/**
 * Payload of {@code booking.service_alert} (notifications emails the recipient in {@code locale}): a provider now
 * serves the place the customer asked about.
 */
public record ServiceAlertPayload(Recipient recipient, String locale, String city, String state, String category,
                                  String serviceName, String providerName, String serviceUrl) {

    /** Who to email; there is no account, only the address the customer left. */
    public record Recipient(String email) {
    }

    static ServiceAlertPayload of(Alert a, ServiceOffering o, Provider p, String serviceUrlBase) {
        return new ServiceAlertPayload(new Recipient(a.email()), a.locale(), a.city(), a.state(), o.category(),
                o.name(), p.displayName(), serviceUrlBase + o.id());
    }
}
