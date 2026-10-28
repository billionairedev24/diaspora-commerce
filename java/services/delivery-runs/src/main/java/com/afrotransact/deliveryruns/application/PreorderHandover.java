package com.afrotransact.deliveryruns.application;

import java.time.Duration;

import com.afrotransact.deliveryruns.port.HandoverCodes;
import com.afrotransact.deliveryruns.port.PreorderOrderRepository;
import com.afrotransact.deliveryruns.port.PreorderSettingsRepository;
import com.afrotransact.deliveryruns.port.PreorderWindowRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Pre-order collaborators of {@link RunService#fromWindow}: windows, their orders, fees, codes and events. */
@Component
class PreorderHandover {

    private final PreorderWindowRepository windows;
    private final PreorderOrderRepository orders;
    private final PreorderSettingsRepository settings;
    private final HandoverCodes codes;
    private final DeliveryRunsEventPublisher events;
    private final FeeBook fees;
    private final Duration sellerPickupLead;

    PreorderHandover(PreorderWindowRepository windows, PreorderOrderRepository orders,
                     PreorderSettingsRepository settings, HandoverCodes codes, DeliveryRunsEventPublisher events,
                     FeeBook fees,
                     @Value("${at.delivery-runs.preorders.seller-pickup-lead:PT24H}") Duration sellerPickupLead) {
        this.fees = fees;
        this.sellerPickupLead = sellerPickupLead;
        this.windows = windows;
        this.orders = orders;
        this.settings = settings;
        this.codes = codes;
        this.events = events;
    }

    PreorderWindowRepository windows() {
        return windows;
    }

    PreorderOrderRepository orders() {
        return orders;
    }

    PreorderSettingsRepository settings() {
        return settings;
    }

    FeeBook fees() {
        return fees;
    }

    /** How long before the delivery window opens the sellers hand their packed orders over (S264). */
    Duration sellerPickupLead() {
        return sellerPickupLead;
    }

    HandoverCodes codes() {
        return codes;
    }

    DeliveryRunsEventPublisher events() {
        return events;
    }
}
