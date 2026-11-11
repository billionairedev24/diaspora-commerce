package com.afrotransact.catalog.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.SellingApprovals;
import com.afrotransact.catalog.port.SellingApprovals.Query;
import com.afrotransact.catalog.port.SellingApprovals.Status;
import com.afrotransact.catalog.port.SellingHoldRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class SellingPublishGateImpl implements SellingPublishGate {

    private static final int MAX_DEPTH = 6;
    private static final String APPLY_PATH = "/selling-approvals";

    private final SellingApprovals approvals;
    private final CategoryRepository categories;
    private final SellingHoldRepository holds;
    private final boolean enabled;

    SellingPublishGateImpl(SellingApprovals approvals, CategoryRepository categories, SellingHoldRepository holds,
                           @Value("${at.catalog.selling-gate.enabled:true}") boolean enabled) {
        this.approvals = approvals;
        this.categories = categories;
        this.holds = holds;
        this.enabled = enabled;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public Status evaluate(ListingOwner owner, Facts facts) {
        if (!enabled || owner.kind() == ListingOwnerKind.PLATFORM) {
            return new Status("SELL", null, List.of(), List.of());
        }
        return approvals.statusOf(owner.orgId(), new Query(categoryCodes(facts.categoryId()), brand(facts),
                facts.productTypeCode(), material(facts), facts.gtin()));
    }

    @Override
    public void requirePublishable(ListingOwner owner, Facts facts) {
        Status status = evaluate(owner, facts);
        if (status.sell()) {
            return;
        }
        Optional<SellingApprovals.Missing> toApply = status.missing().stream().filter(m -> m.canApply()).findFirst();
        String url = "APPLY".equals(status.level()) && toApply.isPresent() ? APPLY_PATH + "?apply="
                + toApply.get().scopeType() + ":" + toApply.get().scopeKey() : APPLY_PATH;
        String message = status.reason() == null ? "This listing cannot go live yet: your store needs approval to sell it."
                : status.reason();
        throw new SellingApprovalRequired(status.level(), message + " Your listing is saved as a draft.",
                status.missing(), url);
    }

    @Override
    public void requireApprovable(Product p) {
        if (enabled && p.categoryId() != null) {
            requirePublishable(p.owner(), factsOf(p));
        }
    }

    @Override
    public void requireResumable(Product p) {
        if (!enabled) {
            return;
        }
        if (holds.isHeld(p.id())) {
            throw new SellingApprovalRequired("APPLY", "This listing is paused because your approval to sell it expired or "
                    + "was withdrawn. Renew it and the listing comes back on its own.", List.of(), APPLY_PATH);
        }
        requireApprovable(p);
    }

    @Override
    public Facts factsOf(Product p) {
        String gtin = p.variants().stream().map(Variant::gtin).filter(g -> g != null && !g.isBlank()).findFirst()
                .orElse(null);
        return new Facts(p.categoryId(), p.productTypeCode(), p.attributes(), gtin);
    }

    /** The node's code and every ancestor's, nearest first (a rule can be about the product type, category or department). */
    private String categoryCodes(UUID categoryId) {
        List<String> codes = new ArrayList<>();
        UUID id = categoryId;
        for (int depth = 0; id != null && depth < MAX_DEPTH; depth++) {
            Optional<CategoryNode> node = categories.findById(id);
            if (node.isEmpty()) {
                break;
            }
            codes.add(node.get().code());
            id = node.get().parentId();
        }
        return codes.isEmpty() ? null : String.join(",", codes);
    }

    private static String brand(Facts f) {
        return f.attributes().get("brand");
    }

    private static String material(Facts f) {
        Map<String, String> a = f.attributes();
        String m = a.get("material");
        return m != null ? m : a.get("metal");
    }
}
