package com.afrotransact.catalog.adapter.out.module;

import com.afrotransact.catalog.port.SellerOffers;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.InProcessLink;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.offers.api.OffersApi;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link SellerOffers} through offers' published API inside this JVM (mode {@code in-process}): the same answer as
 * {@code GET /v1/offers/presence}, entered as catalog's caller principal (scope {@code offers:read}). A refusal or failure is
 * {@link OffersUnavailable}, as over HTTP, so a request to change somebody else's page is refused (fail closed).
 */
@Component
@ConditionalOnModuleMode(caller = "catalog", callee = "offers", value = ModuleMode.IN_PROCESS)
class InProcessSellerOffers implements SellerOffers {

    private final InProcessLink<OffersApi> offers;

    InProcessSellerOffers(ObjectProvider<OffersApi> api, ObjectProvider<CallerPrincipals> callers,
                          ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(OffersApi.class, true, false).length > 0, "catalog", "offers",
                "at.catalog.offers.mode");
        this.offers = new InProcessLink<>("catalog", api::getObject, callers::getObject);
    }

    @Override
    public boolean hasOpenOffer(String sellerOrgId, String atin) {
        try {
            return offers.call(api -> api.hasOpenOffer(sellerOrgId, atin));
        } catch (RuntimeException e) {
            throw new OffersUnavailable("offers refused or failed the in-process presence check for " + sellerOrgId
                    + " on " + atin + ": " + e.getMessage(), e);
        }
    }
}
