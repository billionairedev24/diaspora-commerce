package com.afrotransact.taxreturns.adapter.out.tax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.tax.CollectedBy;
import com.afrotransact.taxreturns.domain.tax.Jurisdiction;
import com.afrotransact.taxreturns.domain.tax.ProviderConnection;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxLineInput;
import com.afrotransact.taxreturns.domain.tax.TaxLineResult;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import com.afrotransact.taxreturns.port.InHouseTaxCalculator;
import com.afrotransact.taxreturns.port.ProviderTaxCalculator;
import com.afrotransact.taxreturns.port.TaxProviderException;
import com.afrotransact.taxreturns.port.TaxTelemetry;
import net.avalara.avatax.rest.client.enums.DocumentType;
import net.avalara.avatax.rest.client.models.AddressLocationInfo;
import net.avalara.avatax.rest.client.models.AddressesModel;
import net.avalara.avatax.rest.client.models.CreateTransactionModel;
import net.avalara.avatax.rest.client.models.LineItemModel;
import net.avalara.avatax.rest.client.models.PingResultModel;
import net.avalara.avatax.rest.client.models.TransactionLineDetailModel;
import net.avalara.avatax.rest.client.models.TransactionLineModel;
import net.avalara.avatax.rest.client.models.TransactionModel;
import org.springframework.stereotype.Component;

/**
 * {@link ProviderTaxCalculator} over Avalara AvaTax REST v2, called through Avalara's official Java SDK
 * ({@code net.avalara.avatax:avatax-rest-v2-api-java}). Every call is a {@code SalesOrder} with {@code commit=false}:
 * AvaTax prices it and records nothing, so it is safe in SHADOW mode and for a charging quote alike.
 *
 * <p>AvaTax prices by address, so each call carries both ends: the ship-to (street when the caller had one, otherwise the
 * city, state, ZIP and country that are known; a street is never invented) and the ship-from of the line (the seller
 * location or fulfilment centre the order ships from). A line without a ship-from address is not sent: it is refused
 * with a plain reason rather than priced from a guessed origin. Likewise a line without an Avalara code in the code map
 * is not sent and never priced at zero. Strict calls refuse the whole call; a charging quote falls back visibly to the
 * rules engine (provider {@code RULES-FALLBACK}).
 *
 * <p>Credentials come from the environment only (AVALARA_ACCOUNT_ID, AVALARA_LICENSE_KEY, AVALARA_COMPANY_CODE,
 * AVALARA_ENVIRONMENT, optional AVALARA_BASE_URL and AVALARA_APP_NAME) and are never logged or put in a message.
 * Addresses are not run through AvaTax address validation (ResolveAddress): the tax call resolves the address itself.
 *
 * <p>This adapter only calculates: nothing is committed to AvaTax, and returns are not reported to it.
 */
@Component
class AvalaraTaxCalculator implements ProviderTaxCalculator, AutoCloseable {

    static final String PROVIDER = "AVALARA";

    private final TaxReturnsProperties.AvalaraTax config;
    private final AvaTaxGateway gateway;
    private final ProviderFallback fallback;
    private final Clock clock;

    AvalaraTaxCalculator(TaxReturnsProperties props, InHouseTaxCalculator rules, TaxTelemetry telemetry, Clock clock) {
        this(props.tax().avalara(), props.tax().avalara().configured()
                ? new SdkAvaTaxGateway(props.tax().avalara()) : null, rules, telemetry, clock);
    }

    /** For tests: any gateway (null when the provider is not configured). */
    AvalaraTaxCalculator(TaxReturnsProperties.AvalaraTax config, AvaTaxGateway gateway, InHouseTaxCalculator rules,
                         TaxTelemetry telemetry, Clock clock) {
        this.config = config;
        this.gateway = gateway;
        this.fallback = new ProviderFallback(rules, telemetry);
        this.clock = clock;
    }

    @Override
    public TaxProviderId provider() {
        return TaxProviderId.AVALARA;
    }

    @Override
    public boolean configured() {
        return config.configured() && gateway != null;
    }

    @Override
    public TaxQuote quote(TaxAddress shipTo, String currency, List<TaxLineInput> lines) {
        List<String> all = lines.stream().map(TaxLineInput::lineId).toList();
        if (!configured()) {
            return fallback.quote(PROVIDER, "not-configured", notConfigured(), shipTo, currency, lines, all);
        }
        List<String> uncoded = uncoded(lines);
        if (!uncoded.isEmpty()) {
            return fallback.quote(PROVIDER, "no-code", "no Avalara code for the outcome of " + uncoded, shipTo, currency,
                    lines, uncoded);
        }
        List<String> noOrigin = withoutShipFrom(lines);
        if (!noOrigin.isEmpty()) {
            return fallback.quote(PROVIDER, "no-ship-from", "no ship-from address for " + noOrigin, shipTo, currency,
                    lines, noOrigin);
        }
        try {
            return call(shipTo, currency, lines);
        } catch (AvaTaxCallException | IllegalStateException ex) {
            return fallback.quote(PROVIDER, "unavailable", ex.getClass().getSimpleName(), shipTo, currency, lines, all);
        }
    }

    @Override
    public TaxQuote quoteStrict(TaxAddress shipTo, String currency, List<TaxLineInput> lines) {
        if (!configured()) {
            throw new TaxProviderException(notConfigured());
        }
        List<String> uncoded = uncoded(lines);
        if (!uncoded.isEmpty()) {
            throw new TaxProviderException("No Avalara code for lines " + uncoded);
        }
        List<String> noOrigin = withoutShipFrom(lines);
        if (!noOrigin.isEmpty()) {
            throw new TaxProviderException("Avalara prices by origin and destination, and no ship-from address was given "
                    + "for lines " + noOrigin + "; it was not asked rather than guess where the order ships from");
        }
        try {
            return call(shipTo, currency, lines);
        } catch (AvaTaxCallException ex) {
            throw new TaxProviderException("Avalara " + ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new TaxProviderException("Avalara AvaTax failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public ProviderConnection testConnection() {
        if (!configured()) {
            return ProviderConnection.notConfigured(TaxProviderId.AVALARA, notConfigured());
        }
        String env = config.environment();
        try {
            PingResultModel ping = gateway.ping();
            if (Boolean.TRUE.equals(ping.getAuthenticated())) {
                String who = ping.getAuthenticatedUserName();
                return new ProviderConnection(TaxProviderId.AVALARA, true, true, who == null || who.isBlank() ? null : who,
                        env, "Avalara accepted the credentials (" + env + ")");
            }
            return new ProviderConnection(TaxProviderId.AVALARA, true, false, null, env,
                    "Avalara answered but did not accept the credentials: check AVALARA_ACCOUNT_ID and "
                            + "AVALARA_LICENSE_KEY, and that AVALARA_ENVIRONMENT matches where the account was created");
        } catch (AvaTaxCallException ex) {
            return new ProviderConnection(TaxProviderId.AVALARA, true, false, null, env, ex.getMessage());
        }
    }

    private static String notConfigured() {
        return "Avalara is not configured: set AVALARA_ACCOUNT_ID, AVALARA_LICENSE_KEY and AVALARA_COMPANY_CODE";
    }

    private static List<String> uncoded(List<TaxLineInput> lines) {
        return lines.stream().filter(l -> l.codeFor(TaxProviderId.AVALARA) == null).map(TaxLineInput::lineId).toList();
    }

    private static List<String> withoutShipFrom(List<TaxLineInput> lines) {
        return lines.stream().filter(l -> !hasOrigin(l.shipFrom())).map(TaxLineInput::lineId).toList();
    }

    private static boolean hasOrigin(TaxAddress a) {
        return a != null && !a.postalCode().isBlank() && !a.state().isBlank();
    }

    private TaxQuote call(TaxAddress shipTo, String currency, List<TaxLineInput> lines) {
        Prepared prepared = prepare(shipTo, currency, lines, null);
        return parse(gateway.createTransaction(prepared.model()), prepared, shipTo, currency, lines);
    }

    /** A request model and which of the caller's line ids each AvaTax line number stands for. */
    record Prepared(CreateTransactionModel model, Map<String, String> lineByNumber) {
    }

    /**
     * Maps a quote to an AvaTax {@code CreateTransactionModel} (a SalesOrder that is not committed). Lines use the
     * provider code of the code map for the line's outcome; a line without one is left out (callers refuse such lines
     * beforehand, so nothing is ever sent as a guess).
     *
     * <p>When every line ships from the same place the document carries {@code addresses.shipFrom} and
     * {@code addresses.shipTo}; when lines ship from different places each line carries both ends.
     */
    Prepared prepare(TaxAddress shipTo, String currency, List<TaxLineInput> lines, String reference) {
        List<TaxLineInput> coded = lines.stream().filter(l -> l.codeFor(TaxProviderId.AVALARA) != null).toList();
        boolean oneOrigin = coded.stream().map(TaxLineInput::shipFrom).distinct().count() <= 1;
        List<LineItemModel> items = new ArrayList<>();
        Map<String, String> byNumber = new HashMap<>();
        int n = 1;
        for (TaxLineInput l : coded) {
            String number = Integer.toString(n++);
            byNumber.put(number, l.lineId());
            items.add(item(number, l.quantity(), l.taxableItemsMinor(), l.codeFor(TaxProviderId.AVALARA),
                    l.atin() == null ? l.lineId() : l.atin(), l.atin() == null ? l.lineId() : l.atin(),
                    oneOrigin ? null : ends(l.shipFrom(), shipTo)));
            if (l.shippingMinor() > 0) {
                String shipNumber = Integer.toString(n++);
                byNumber.put(shipNumber, "ship:" + l.lineId());
                items.add(item(shipNumber, 1, l.shippingMinor(), config.shippingTaxCode(), "shipping", "Shipping",
                        oneOrigin ? null : ends(l.shipFrom(), shipTo)));
            }
        }
        CreateTransactionModel model = new CreateTransactionModel();
        model.setType(DocumentType.SalesOrder);
        model.setCompanyCode(config.companyCode());
        model.setDate(Date.from(LocalDate.now(clock).atStartOfDay(ZoneId.systemDefault()).toInstant()));
        model.setCustomerCode(reference == null ? "guest" : reference);
        model.setCurrencyCode(currency);
        model.setCommit(false);
        model.setAddresses(ends(oneOrigin && !coded.isEmpty() ? coded.getFirst().shipFrom() : null, shipTo));
        model.setLines(new ArrayList<>(items));
        return new Prepared(model, byNumber);
    }

    private static LineItemModel item(String number, int quantity, long amountMinor, String taxCode, String itemCode,
                                      String description, AddressesModel addresses) {
        LineItemModel item = new LineItemModel();
        item.setNumber(number);
        item.setQuantity(BigDecimal.valueOf(quantity));
        item.setAmount(minorToMajor(amountMinor));
        item.setTaxCode(taxCode);
        item.setItemCode(itemCode);
        item.setDescription(description);
        item.setTaxIncluded(false);
        if (addresses != null) {
            item.setAddresses(addresses);
        }
        return item;
    }

    private static AddressesModel ends(TaxAddress from, TaxAddress to) {
        AddressesModel a = new AddressesModel();
        if (from != null) {
            a.setShipFrom(location(from));
        }
        a.setShipTo(location(to));
        return a;
    }

    /** Only what is known: a blank street or city is left out, never filled in. */
    private static AddressLocationInfo location(TaxAddress a) {
        AddressLocationInfo info = new AddressLocationInfo();
        if (!a.line1().isBlank()) {
            info.setLine1(a.line1());
        }
        if (a.city() != null && !a.city().isBlank()) {
            info.setCity(a.city());
        }
        info.setRegion(a.state());
        info.setCountry(a.country());
        info.setPostalCode(a.postalCode());
        return info;
    }

    /** Reads AvaTax's transaction: tax and combined rate per line (the shipping line's tax is folded into its line). */
    TaxQuote parse(TransactionModel response, Prepared prepared, TaxAddress shipTo, String currency,
                   List<TaxLineInput> lines) {
        Map<String, Long> tax = new HashMap<>();
        Map<String, BigDecimal> rate = new HashMap<>();
        List<TransactionLineModel> answered = response == null || response.getLines() == null ? List.of()
                : response.getLines();
        for (TransactionLineModel l : answered) {
            String id = prepared.lineByNumber().get(l.getLineNumber());
            if (id == null) {
                continue;
            }
            tax.put(id, minor(Objects.requireNonNullElse(l.getTax(), BigDecimal.ZERO)));
            BigDecimal combined = BigDecimal.ZERO;
            if (l.getDetails() != null) {
                for (TransactionLineDetailModel d : l.getDetails()) {
                    combined = combined.add(Objects.requireNonNullElse(d.getRate(), BigDecimal.ZERO));
                }
            }
            rate.put(id, combined);
        }
        if (tax.isEmpty() && !prepared.lineByNumber().isEmpty()) {
            throw new IllegalStateException("AvaTax returned no lines");
        }
        List<TaxLineResult> out = new ArrayList<>(lines.size());
        boolean collected = false;
        for (TaxLineInput l : lines) {
            Long itemTax = tax.get(l.lineId());
            if (itemTax == null) {
                throw new IllegalStateException("AvaTax returned no result for line " + l.lineId());
            }
            long shipTax = tax.getOrDefault("ship:" + l.lineId(), 0L);
            long total = Math.addExact(itemTax, shipTax);
            collected |= total > 0;
            BigDecimal combined = rate.getOrDefault(l.lineId(), BigDecimal.ZERO).setScale(6, RoundingMode.HALF_UP)
                    .stripTrailingZeros();
            long taxable = l.taxableItemsMinor() + (shipTax > 0 ? l.shippingMinor() : 0L);
            out.add(new TaxLineResult(l.lineId(), l.sellerOrgId(), l.category(), taxable, combined, total,
                    shipTo.stateJurisdiction(), null, total > 0 ? CollectedBy.MARKETPLACE : CollectedBy.NOT_COLLECTED,
                    shipTax, l.taxCode(), null, Jurisdiction.ADDRESS));
        }
        return new TaxQuote(currency, shipTo.stateJurisdiction(),
                collected ? CollectedBy.MARKETPLACE : CollectedBy.NOT_COLLECTED, out, PROVIDER);
    }

    private static long minor(BigDecimal major) {
        return major.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** Two-decimal currencies only (USD at launch); other currencies need the currency's exponent. */
    private static BigDecimal minorToMajor(long minor) {
        return BigDecimal.valueOf(minor, 2);
    }

    @Override
    public void close() {
        if (gateway instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
