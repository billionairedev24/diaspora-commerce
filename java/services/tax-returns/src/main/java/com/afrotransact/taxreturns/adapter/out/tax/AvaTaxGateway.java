package com.afrotransact.taxreturns.adapter.out.tax;

import net.avalara.avatax.rest.client.models.CreateTransactionModel;
import net.avalara.avatax.rest.client.models.PingResultModel;
import net.avalara.avatax.rest.client.models.TransactionModel;

/**
 * The two AvaTax REST v2 calls this service makes, over Avalara's Java SDK. An interface so the calculator can be tested
 * without a network and so the SDK stays behind one class.
 */
interface AvaTaxGateway {

    /** {@code POST /api/v2/transactions/create}. */
    TransactionModel createTransaction(CreateTransactionModel model);

    /** {@code GET /api/v2/utilities/ping}: whether the credentials are accepted, and for whom. */
    PingResultModel ping();
}
