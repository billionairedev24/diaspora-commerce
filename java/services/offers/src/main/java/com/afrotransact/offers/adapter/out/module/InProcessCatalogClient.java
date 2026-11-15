package com.afrotransact.offers.adapter.out.module;

import java.util.Optional;
import java.util.function.Function;

import com.afrotransact.catalog.api.CatalogLookupApi;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.InProcessLink;
import com.afrotransact.offers.port.CatalogClient;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * {@link CatalogClient} through catalog's published API inside this JVM (mode {@code in-process}): the same answers as
 * {@code POST /v1/catalog/offer-eligibility}, {@code GET /v1/public/variants/{atin}} and the GTIN lookup, entered as offers'
 * caller principal (scope {@code catalog:read}). "No such live variant" is an empty answer, as a 404 is over HTTP; anything
 * else, including a refusal of the principal, is {@link CatalogUnavailableException}.
 */
@Component
@ConditionalOnExpression("'${at.offers.catalog.mode:${at.offers.mode:http}}' == 'in-process'")
class InProcessCatalogClient implements CatalogClient {

    private final InProcessLink<CatalogLookupApi> catalog;

    InProcessCatalogClient(ObjectProvider<CatalogLookupApi> api, ObjectProvider<CallerPrincipals> callers,
                           ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(CatalogLookupApi.class, true, false).length > 0, "offers", "catalog",
                "at.offers.catalog.mode");
        this.catalog = new InProcessLink<>("offers", api::getObject, callers::getObject);
    }

    @Override
    public Eligibility eligibility(String variantAtin, String sellerOrgId) {
        CatalogLookupApi.OfferEligibility e = ask(api -> api.offerEligibility(variantAtin, sellerOrgId))
                .orElseThrow(() -> new CatalogUnavailableException("catalog gave no eligibility answer for "
                        + variantAtin, null));
        return new Eligibility(e.canOffer(), e.blockers().stream().map(b -> new Blocker(b.code(), b.message())).toList(),
                e.allowedConditions());
    }

    @Override
    public Optional<CatalogVariant> liveVariant(String atin) {
        return ask(api -> api.liveVariant(atin)).map(InProcessCatalogClient::variant);
    }

    @Override
    public Optional<CatalogVariant> liveVariantByGtin(String gtin) {
        return ask(api -> api.liveVariantByGtin(gtin)).map(InProcessCatalogClient::variant);
    }

    private <T> T ask(Function<CatalogLookupApi, T> call) {
        try {
            return catalog.call(call);
        } catch (RuntimeException ex) {
            throw new CatalogUnavailableException("catalog refused or failed the in-process call: " + ex.getMessage(), ex);
        }
    }

    private static CatalogVariant variant(CatalogLookupApi.LiveVariant v) {
        return new CatalogVariant(v.productId(), v.productAtin(), v.variantAtin(), v.gtin(), v.categoryId(), v.title(),
                v.sellerOrgId());
    }
}
