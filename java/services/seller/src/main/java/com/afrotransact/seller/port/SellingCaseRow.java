package com.afrotransact.seller.port;

import com.afrotransact.seller.domain.ApplicationCase;
import com.afrotransact.seller.domain.SellingApplication;

/** A selling application with who holds it. */
public record SellingCaseRow(SellingApplication application, ApplicationCase workflow) {
}
