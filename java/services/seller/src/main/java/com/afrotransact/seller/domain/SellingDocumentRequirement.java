package com.afrotransact.seller.domain;

import java.util.Objects;

/** One document a rule asks for; {@code optional} means "or tell us why you cannot" (an exemption with a reason). */
public record SellingDocumentRequirement(SellingDocumentKind kind, boolean optional) {

    public SellingDocumentRequirement {
        Objects.requireNonNull(kind, "kind");
    }
}
