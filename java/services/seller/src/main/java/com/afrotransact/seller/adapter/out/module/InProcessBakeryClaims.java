package com.afrotransact.seller.adapter.out.module;

import java.util.List;

import com.afrotransact.catalog.api.CatalogLookupApi;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.seller.port.BakeryClaims;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * {@link BakeryClaims} through catalog's published API inside this JVM (mode {@code in-process}): the same answer as
 * {@code GET /v1/internal/bakery-claims}, entered as seller's caller principal (scope {@code catalog:bakery-claims.read}). A
 * refusal or failure propagates as it does over HTTP (the caller of the port handles it).
 */
@Component
@ConditionalOnExpression("'${at.seller.catalog.mode:${at.seller.mode:http}}' == 'in-process'")
class InProcessBakeryClaims implements BakeryClaims {

    private final SellerModuleCalls<CatalogLookupApi> catalog;

    InProcessBakeryClaims(ObjectProvider<CatalogLookupApi> api, ObjectProvider<CallerPrincipals> callers,
                          ListableBeanFactory beans) {
        this.catalog = new SellerModuleCalls<>(CatalogLookupApi.class, "catalog", api, callers, beans);
    }

    @Override
    public List<String> organisations() {
        return catalog.raw(CatalogLookupApi::bakeryClaimOrganisations).stream().filter(s -> s != null && !s.isBlank())
                .toList();
    }
}
