package com.afrotransact.deliveryruns.application;

import com.afrotransact.deliveryruns.port.HandoverCodes;
import com.afrotransact.deliveryruns.port.PreorderOrderRepository;
import com.afrotransact.deliveryruns.port.PreorderSettingsRepository;
import com.afrotransact.deliveryruns.port.PreorderWindowRepository;
import org.springframework.stereotype.Component;

/** Pre-order collaborators of {@link RunService#fromWindow}: windows, their orders, fees, codes and events. */
@Component
class PreorderHandover {

    private final PreorderWindowRepository windows;
    private final PreorderOrderRepository orders;
    private final PreorderSettingsRepository settings;
    private final HandoverCodes codes;
    private final DeliveryRunsEventPublisher events;

    PreorderHandover(PreorderWindowRepository windows, PreorderOrderRepository orders,
                     PreorderSettingsRepository settings, HandoverCodes codes, DeliveryRunsEventPublisher events) {
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

    HandoverCodes codes() {
        return codes;
    }

    DeliveryRunsEventPublisher events() {
        return events;
    }
}
