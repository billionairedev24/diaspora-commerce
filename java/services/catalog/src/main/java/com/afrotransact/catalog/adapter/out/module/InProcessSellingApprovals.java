package com.afrotransact.catalog.adapter.out.module;

import java.util.List;

import com.afrotransact.catalog.port.SellingApprovals;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.InProcessLink;
import com.afrotransact.seller.api.SellerLookupApi;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * {@link SellingApprovals} through seller's published API inside this JVM (mode {@code in-process}): the same answer as
 * {@code GET /v1/orgs/{orgId}/selling-status}, entered as catalog's caller principal (scope {@code sellers:catalog-scope.read}).
 * Not cached: there is no round trip to save, and an approval granted a moment ago shows at once. A failure, including a
 * refusal of the principal, is {@link SellingUnavailable}, so the catalog refuses (fail closed).
 */
@Component
@ConditionalOnExpression("'${at.catalog.seller.mode:${at.catalog.mode:http}}' == 'in-process'")
class InProcessSellingApprovals implements SellingApprovals {

    private final InProcessLink<SellerLookupApi> seller;

    InProcessSellingApprovals(ObjectProvider<SellerLookupApi> api, ObjectProvider<CallerPrincipals> callers,
                              ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(SellerLookupApi.class, true, false).length > 0, "catalog", "seller",
                "at.catalog.seller.mode");
        this.seller = new InProcessLink<>("catalog", api::getObject, callers::getObject);
    }

    @Override
    public Status statusOf(String orgId, Query q) {
        try {
            SellerLookupApi.SellingStatus body = seller.call(api -> api.sellingStatus(orgId, blankToNull(q.categoryCodes()),
                    blankToNull(q.brand()), blankToNull(q.productTypeCode()), blankToNull(q.material()),
                    blankToNull(q.gtin())));
            if (body == null || body.level() == null) {
                throw new SellingUnavailable("seller returned no selling status for " + orgId, null);
            }
            List<Missing> missing = body.missing().stream().map(m -> new Missing(m.scopeType(), m.scopeKey(), m.label(),
                    m.level(), m.state(), m.message(), m.canApply())).toList();
            return new Status(body.level(), body.reason(), missing, body.pendingFacts());
        } catch (SellingUnavailable e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SellingUnavailable("seller could not say whether " + orgId + " may sell this: " + e.getMessage(), e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
