package com.afrotransact.taxreturns.port;

import com.afrotransact.taxreturns.domain.tax.TaxMethod;

/** Which method works out sales tax right now (what staff chose in Settings, else the configured default). */
public interface TaxMethodSource {

    TaxMethod method();
}
