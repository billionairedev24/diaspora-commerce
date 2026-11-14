package com.afrotransact.taxreturns.adapter.out.tax;

import java.io.InterruptedIOException;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.google.gson.JsonParseException;
import net.avalara.avatax.rest.client.AvaTaxClient;
import net.avalara.avatax.rest.client.AvaTaxClientException;
import net.avalara.avatax.rest.client.models.CreateTransactionModel;
import net.avalara.avatax.rest.client.models.ErrorInfo;
import net.avalara.avatax.rest.client.models.PingResultModel;
import net.avalara.avatax.rest.client.models.TransactionModel;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClientBuilder;

/**
 * {@link AvaTaxGateway} over {@code net.avalara.avatax:avatax-rest-v2-api-java} ({@link AvaTaxClient}). The client is
 * built once from the environment-based settings: application name and version for the SDK's client header, the account
 * id and license key as HTTP Basic credentials (set on the SDK, never logged and never put in a message), the host from
 * {@code AVALARA_ENVIRONMENT} or {@code AVALARA_BASE_URL}, and one timeout for connect, connection lease and read.
 */
final class SdkAvaTaxGateway implements AvaTaxGateway, AutoCloseable {

    private static final String MACHINE_NAME = "tax-returns";

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AvaTaxClient client;

    SdkAvaTaxGateway(TaxReturnsProperties.AvalaraTax config) {
        int millis = (int) Math.min(Integer.MAX_VALUE, config.timeout().toMillis());
        RequestConfig timeouts = RequestConfig.custom().setConnectTimeout(millis).setConnectionRequestTimeout(millis)
                .setSocketTimeout(millis).build();
        HttpClientBuilder http = HttpClientBuilder.create().setDefaultRequestConfig(timeouts).disableAutomaticRetries();
        this.client = new AvaTaxClient(config.appName(), config.appVersion(), MACHINE_NAME, config.baseUrl(), executor,
                http).withSecurity(config.accountId(), config.licenseKey());
    }

    @Override
    public TransactionModel createTransaction(CreateTransactionModel model) {
        try {
            return client.createTransaction(null, model);
        } catch (Exception ex) {
            throw translate(ex);
        }
    }

    @Override
    public PingResultModel ping() {
        try {
            return client.ping();
        } catch (Exception ex) {
            throw translate(ex);
        }
    }

    /** Maps whatever the SDK threw to one exception; the message names AvaTax's own error, never the request. */
    static AvaTaxCallException translate(Exception ex) {
        if (ex instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof AvaTaxClientException refused && refused.getErrorResult() != null
                    && refused.getErrorResult().getError() != null) {
                ErrorInfo e = refused.getErrorResult().getError();
                String code = e.getCode() == null ? "error" : e.getCode().toString();
                return new AvaTaxCallException(AvaTaxCallException.Kind.REFUSED, "AvaTax refused the request (" + code
                        + "): " + shorten(e.getMessage()), ex);
            }
            if (t instanceof JsonParseException || t instanceof IllegalStateException) {
                return new AvaTaxCallException(AvaTaxCallException.Kind.REFUSED,
                        "AvaTax answered with something that is not a readable AvaTax response (an error page or a 5xx)", ex);
            }
            if (t instanceof InterruptedIOException || t instanceof TimeoutException) {
                return new AvaTaxCallException(AvaTaxCallException.Kind.TIMEOUT, "AvaTax did not answer in time", ex);
            }
            if (t instanceof SocketException) {
                return new AvaTaxCallException(AvaTaxCallException.Kind.UNREACHABLE,
                        "AvaTax could not be reached (" + t.getClass().getSimpleName() + ")", ex);
            }
        }
        return new AvaTaxCallException(AvaTaxCallException.Kind.UNREACHABLE,
                "AvaTax call failed (" + ex.getClass().getSimpleName() + ")", ex);
    }

    private static String shorten(String message) {
        if (message == null || message.isBlank()) {
            return "no message";
        }
        return message.length() <= 200 ? message : message.substring(0, 200);
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
