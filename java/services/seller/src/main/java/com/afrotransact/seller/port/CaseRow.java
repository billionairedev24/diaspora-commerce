package com.afrotransact.seller.port;

import com.afrotransact.seller.domain.Application;
import com.afrotransact.seller.domain.ApplicationCase;

/** An application with who holds it. */
public record CaseRow(Application application, ApplicationCase workflow) {
}
