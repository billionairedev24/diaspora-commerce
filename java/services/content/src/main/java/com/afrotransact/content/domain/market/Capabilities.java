package com.afrotransact.content.domain.market;

import java.util.Map;

import com.afrotransact.content.domain.ContentRuleViolation;

/**
 * Capability switches of one state or province. The marketplace is the master switch: when it is off every
 * other capability is off too (the compact constructor enforces it).
 *
 * @param marketplace     products can be sold and delivered
 * @param services        service bookings
 * @param food            food and perishables
 * @param pickup          pickup points and seller pickup
 * @param taxRegistration AfroTransact is registered to collect sales tax (marketplace facilitator)
 */
public record Capabilities(boolean marketplace, boolean services, boolean food, boolean pickup,
                           boolean taxRegistration) {

    public Capabilities {
        if (!marketplace) {
            services = false;
            food = false;
            pickup = false;
            taxRegistration = false;
        }
    }

    public static Capabilities allOn() {
        return new Capabilities(true, true, true, true, true);
    }

    public static Capabilities allOff() {
        return new Capabilities(false, false, false, false, false);
    }

    /**
     * Strict constructor for edits: rejects switching a capability on while the marketplace is off, instead of
     * silently dropping it.
     */
    public static Capabilities strict(boolean marketplace, boolean services, boolean food, boolean pickup,
                                      boolean taxRegistration) {
        if (!marketplace && (services || food || pickup || taxRegistration)) {
            throw marketplaceOff();
        }
        return new Capabilities(marketplace, services, food, pickup, taxRegistration);
    }

    public boolean has(Capability c) {
        return switch (c) {
            case MARKETPLACE -> marketplace;
            case SERVICES -> services;
            case FOOD -> food;
            case PICKUP -> pickup;
            case TAX_REGISTRATION -> taxRegistration;
        };
    }

    /**
     * One switch changed. Turning the marketplace off turns everything off; turning another capability on while
     * the marketplace is off is rejected.
     */
    public Capabilities with(Capability c, boolean on) {
        if (c != Capability.MARKETPLACE && on && !marketplace) {
            throw marketplaceOff();
        }
        return switch (c) {
            case MARKETPLACE -> new Capabilities(on, services, food, pickup, taxRegistration);
            case SERVICES -> new Capabilities(marketplace, on, food, pickup, taxRegistration);
            case FOOD -> new Capabilities(marketplace, services, on, pickup, taxRegistration);
            case PICKUP -> new Capabilities(marketplace, services, food, on, taxRegistration);
            case TAX_REGISTRATION -> new Capabilities(marketplace, services, food, pickup, on);
        };
    }

    public boolean anyOn() {
        return marketplace;
    }

    private static ContentRuleViolation marketplaceOff() {
        return new ContentRuleViolation("content.marketplace-off",
                "Turn the marketplace on in this state before enabling other capabilities",
                Map.of("marketplace", "The marketplace is off in this state"));
    }
}
