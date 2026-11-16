package com.afrotransact.ai.port;

import java.util.List;

import com.afrotransact.ai.port.AccountReader.Result;
import com.afrotransact.ai.port.AccountReader.ReturnSummary;

/** The shopper's returns and refunds (tax-returns {@code GET /v1/returns}); one edge, ai-gateway -> tax-returns. */
public interface ReturnHistory {

    /** @param orderNumber narrows to one order, blank for all */
    Result<List<ReturnSummary>> mine(Shopper shopper, String orderNumber);
}
