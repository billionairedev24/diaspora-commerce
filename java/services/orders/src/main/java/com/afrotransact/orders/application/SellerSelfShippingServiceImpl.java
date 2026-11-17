package com.afrotransact.orders.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import com.afrotransact.orders.domain.OrdersStateConflict;
import com.afrotransact.orders.domain.SellerSelfShipping;
import com.afrotransact.orders.port.SellerSelfShippingRepository;
import com.afrotransact.orders.port.ShippingSettingsRepository;
import com.afrotransact.platform.security.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SellerSelfShippingServiceImpl implements SellerSelfShippingService {

    private final OrdersAccess access;
    private final ShippingSettingsRepository settings;
    private final SellerSelfShippingRepository sellers;
    private final Clock clock;

    SellerSelfShippingServiceImpl(OrdersAccess access, ShippingSettingsRepository settings, SellerSelfShippingRepository sellers,
                                  Clock clock) {
        this.access = access;
        this.settings = settings;
        this.sellers = sellers;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Setting mine(String sellerOrgId, String currency) {
        String org = access.sellerOrgToManage(sellerOrgId);
        String code = code(currency);
        boolean on = switchOn(code);
        return sellers.find(org).filter(r -> r.currency().equals(code))
                .map(r -> new Setting(org, code, on, r.enabled(), r.chargeMinor(), r.version()))
                .orElse(new Setting(org, code, on, false, 0, 0));
    }

    @Override
    @Transactional
    public Setting replace(String sellerOrgId, String currency, boolean enabled, long chargeMinor) {
        String org = access.sellerOrgToManage(sellerOrgId);
        String code = code(currency);
        if (!switchOn(code)) {
            throw new OrdersStateConflict("orders.self-shipping-disabled", "Sellers cannot ship orders themselves "
                    + "yet. AfroTransact has not turned this on, so there is no shipping charge to set.");
        }
        Actor actor = access.current();
        Instant now = Instant.now(clock);
        SellerSelfShipping next = sellers.find(org).map(r -> r.next(chargeMinor, enabled, actor.subject(), now))
                .orElseGet(() -> new SellerSelfShipping(org, code, chargeMinor, enabled, 1, now, actor.subject()));
        sellers.save(next);
        return new Setting(org, code, true, next.enabled(), next.chargeMinor(), next.version());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Setting> offered(Collection<String> sellerOrgIds, String currency) {
        String code = code(currency);
        if (!switchOn(code)) {
            return List.of();
        }
        return sellers.findAll(sellerOrgIds).stream().filter(r -> r.enabled() && r.currency().equals(code))
                .map(r -> new Setting(r.sellerOrgId(), code, true, true, r.chargeMinor(), r.version())).toList();
    }

    private boolean switchOn(String code) {
        return settings.find(code).map(s -> s.sellerSelfShippingEnabled()).orElse(false);
    }

    private static String code(String currency) {
        return currency == null || currency.isBlank() ? "USD" : currency.trim().toUpperCase(Locale.ROOT);
    }
}
