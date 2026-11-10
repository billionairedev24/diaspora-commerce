package com.afrotransact.taxreturns.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.taxreturns.application.ClassificationResolveService.ResolveCommand;
import com.afrotransact.taxreturns.application.VariantClassificationService.ApproveCommand;
import com.afrotransact.taxreturns.application.VariantClassificationService.CatalogFactsChanged;
import com.afrotransact.taxreturns.application.VariantClassificationService.Variant;
import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.profile.ApprovalPolicy;
import com.afrotransact.taxreturns.domain.profile.FactKind;
import com.afrotransact.taxreturns.domain.profile.ProfileFact;
import com.afrotransact.taxreturns.domain.profile.Resolution;
import com.afrotransact.taxreturns.domain.profile.TaxMapFixture;
import com.afrotransact.taxreturns.domain.profile.TaxProfile;
import com.afrotransact.taxreturns.domain.tax.DefaultTaxRules;
import com.afrotransact.taxreturns.domain.tax.LocalTaxRate;
import com.afrotransact.taxreturns.domain.tax.StateTaxRule;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxLineResult;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import com.afrotransact.taxreturns.domain.tax.TaxRateTable;
import com.afrotransact.taxreturns.port.ProductFacts;
import com.afrotransact.taxreturns.port.TaxCalculator;
import com.afrotransact.taxreturns.port.TaxMethodSource;
import com.afrotransact.taxreturns.port.TaxRateRepository;
import com.afrotransact.taxreturns.port.TaxTelemetry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The category tax verification matrix, tax-returns side: for each of the 105 decision rows (one per Appendix A scenario
 * over the 73 categories) the row's facts and the seller's behaviour become a classification, tax review approves it where
 * the rules allow, and a checkout quote for Texas addresses is taken with the real rules engine over the Texas ZIP table
 * (migration V4: 6.25 percent state plus the local rate of the ZIP). Expected: EXEMPT is 0, TAXABLE is state plus local on the
 * discounted items and the delivery, anything gated or missing comes back BLOCKED with the plain message and no rate.
 * Negative cases per category: each required fact removed gives MISSING_FACTS with the plain message and no outcome, text in a
 * number is refused. Writes {@code target/category-tax-matrix/tax-returns.json}.
 */
class CategoryTaxMatrixTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> ZIPS = List.of("77002", "78205", "79401");
    private static final long UNIT = 4_000;
    private static final long DISCOUNT = 500;
    private static final long SHIPPING = 799;

    private final TestRules rules = new TestRules();
    private final InMemoryClassifications records = new InMemoryClassifications();
    private final TaxTelemetry telemetry = mock(TaxTelemetry.class);
    private final DomainEventPublisher publisher = mock(DomainEventPublisher.class);
    private final TaxMethodSource methods = mock(TaxMethodSource.class);
    private final Map<String, ProductFacts.Facts> catalogFacts = new HashMap<>();
    private final TaxRateTable table = texasTable();

    @Test
    @SuppressWarnings("unchecked")
    void everyDecisionRowResolvesQuotesAndBlocksAsTheDatasetSays() throws IOException {
        TaxReturnsProperties props = new TaxReturnsProperties(new TaxReturnsProperties.Tax(null, null, null,
                new TaxReturnsProperties.Classification(null, ApprovalPolicy.ALL, false, null),
                new TaxReturnsProperties.Shadow(null, null, false, null)), null, null, null, null);
        VariantClassificationService classifications = new VariantClassificationServiceImpl(records, rules.engine,
                new ListingDecisions(records), new ClassificationEvents(publisher, telemetry), props,
                TestActors.staff("*"), TestRules.CLOCK);
        ProductFacts facts = atins -> {
            Map<String, ProductFacts.Facts> out = new HashMap<>(catalogFacts);
            out.keySet().retainAll(atins);
            return out;
        };
        LineDecisionService decisions = new LineDecisionServiceImpl(records, classifications, facts, rules.engine,
                new ListingDecisions(records), props, telemetry, TestRules.CLOCK);
        TaxCalculator calculator = (shipTo, currency, lines) -> new DefaultTaxRules()
                .quote(table.jurisdiction(shipTo), shipTo, currency, lines);
        when(methods.shadowProvider()).thenReturn(Optional.empty());
        TaxService service = new TaxServiceImpl(calculator, mock(TaxRateRepository.class), decisions,
                mock(TaxShadowService.class), methods, telemetry, props, TestActors.staff("tax:quote"), TestRules.CLOCK);
        ClassificationResolveService resolver = new ClassificationResolveServiceImpl(rules.engine, TestRules.props(null),
                telemetry, TestActors.staff("tax:classify"));

        Map<String, Object> scenarios = TaxMapFixture.read("scenarios.json");
        List<Map<String, Object>> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Map<String, TaxProfile> profiles = rules.profiles;
        for (Object o : (List<Object>) scenarios.get("scenarios")) {
            Map<String, Object> s = (Map<String, Object>) o;
            String category = (String) s.get("categoryCode");
            Map<String, Object> inputs = (Map<String, Object>) s.get("inputs");
            Map<String, Object> expect = (Map<String, Object>) s.get("expect");
            Map<String, Object> variantFacts = (Map<String, Object>) inputs.get("facts");
            Map<String, Object> behaviour = (Map<String, Object>) inputs.get("sellerBehaviour");
            int n = ((Number) s.get("scenario")).intValue();
            String id = "scenario " + n + " (" + category + ")";
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("scenario", n);
            r.put("categoryCode", category);
            r.put("rowId", s.get("rowId"));
            r.put("expected", expect);

            // (c) resolve
            Resolution res = resolver.resolve(new ResolveCommand(category, null, variantFacts, behaviour,
                    (Map<String, Object>) inputs.get("context"), "TX", null));
            Map<String, Object> resolved = new LinkedHashMap<>();
            resolved.put("status", res.status().name());
            resolved.put("outcome", res.outcome() == null ? null : res.outcome().name());
            resolved.put("internalTaxCode", res.internalTaxCode());
            resolved.put("stripeCode", res.providerCodes().get("STRIPE"));
            r.put("resolve", resolved);
            boolean ok = res.status().name().equals(expect.get("resolveStatus"))
                    && String.valueOf(res.outcome()).equals(String.valueOf(expect.get("outcome")))
                    && res.internalTaxCode().equals(expect.get("internalTaxCode"))
                    && java.util.Objects.equals(res.providerCodes().get("STRIPE"), expect.get("stripeCode"));
            if (!ok) {
                failures.add(id + ": resolve gave " + resolved + ", expected " + expect);
            }

            // (d) a checkout quote for Texas: stored decision, tax review, quote lines. The tax holiday calendar is not built
            // (TX-08): the exemption window is never active at quote time, so a row that needs it is priced as the ordinary
            // sale of the same category (the safe direction); the resolve above still reports the row as the dataset says.
            Map<String, Object> quoteExpect = expect;
            if (Boolean.TRUE.equals(((Map<String, Object>) inputs.get("context")).get("exemption_window_active"))) {
                for (Object other : (List<Object>) scenarios.get("scenarios")) {
                    Map<String, Object> o2 = (Map<String, Object>) other;
                    Map<String, Object> ctx2 = (Map<String, Object>) ((Map<String, Object>) o2.get("inputs")).get("context");
                    if (category.equals(o2.get("categoryCode")) && !Boolean.TRUE.equals(ctx2.get("exemption_window_active"))) {
                        quoteExpect = (Map<String, Object>) o2.get("expect");
                        r.put("limitation", "TX-08: the tax holiday calendar is not built, the quote prices the ordinary sale");
                        break;
                    }
                }
            }
            String atin = String.format("ATMX%05d", n);
            classifications.onCatalogFactsChanged(new CatalogFactsChanged(UUID.randomUUID(), "org_a", category, category,
                    behaviour, List.of(new Variant(atin, null, variantFacts, false)), "seller_1", TestRules.CLOCK.instant()));
            String approval;
            try {
                classifications.approve(atin, new ApproveCommand(null, null, null, null));
                approval = "APPROVED";
            } catch (RuntimeException e) {
                approval = "NOT_APPROVABLE";
            }
            r.put("taxReview", approval);
            boolean chargeable = "RESOLVED".equals(expect.get("resolveStatus"));
            if (chargeable != "APPROVED".equals(approval)) {
                failures.add(id + ": tax review " + approval + " but the rules say " + expect.get("resolveStatus"));
                ok = false;
            }
            List<Map<String, Object>> quotes = new ArrayList<>();
            // taxable lines are priced in three ZIPs (two local rates and none); exempt and blocked ones in one
            List<String> zips = chargeable && "TAXABLE".equals(quoteExpect.get("outcome")) ? ZIPS : List.of(ZIPS.getFirst());
            for (String zip : zips) {
                TaxQuote q = service.quote(new TaxQuoteCommand("USD", new TaxAddress("US", "TX", zip, "Texas"),
                        List.of(new TaxQuoteCommand.Line("1", atin, "org_a", null, null, 1, UNIT, DISCOUNT, SHIPPING,
                                behaviour))));
                TaxLineResult line = q.lines().getFirst();
                Map<String, Object> qr = new LinkedHashMap<>();
                qr.put("zip", zip);
                qr.put("decisionStatus", line.decision().status().name());
                qr.put("outcome", line.decision().outcome() == null ? null : line.decision().outcome().name());
                qr.put("internalTaxCode", line.decision().internalTaxCode());
                qr.put("stripeCode", line.decision().providerCodes().get("STRIPE"));
                qr.put("ruleVersion", line.decision().ruleVersion());
                qr.put("rate", line.rate() == null ? null : line.rate().toPlainString());
                qr.put("taxMinor", line.taxMinor());
                qr.put("localJurisdiction", line.localJurisdiction());
                qr.put("addressPrecision", line.addressPrecision());
                qr.put("blockedReason", line.decision().blockedReason());
                String problem = null;
                if (!chargeable) {
                    if (!line.decision().isBlocked() || line.rate() != null || line.taxMinor() != 0
                            || line.decision().blockedReason() == null
                            || !line.decision().blockedReason().startsWith("This item can't be bought right now")) {
                        problem = "an undecided line was not blocked with the plain message and no rate";
                    }
                } else if ("EXEMPT".equals(quoteExpect.get("outcome"))) {
                    if (line.taxMinor() != 0 || !"RESOLVED".equals(line.decision().status().name())
                            || !"EXEMPT".equals(qr.get("outcome")) || !quoteExpect.get("internalTaxCode").equals(line.decision().internalTaxCode())
                            || !java.util.Objects.equals(quoteExpect.get("stripeCode"), qr.get("stripeCode"))) {
                        problem = "an exempt line was not priced at zero with its decision";
                    }
                } else {
                    BigDecimal rate = stateRate().add(localRate(zip));
                    long want = BigDecimal.valueOf(UNIT - DISCOUNT + SHIPPING).multiply(rate).setScale(0, RoundingMode.HALF_UP)
                            .longValueExact();
                    qr.put("expectedRate", rate.toPlainString());
                    qr.put("expectedTaxMinor", want);
                    if (line.taxMinor() != want || line.rate().compareTo(rate) != 0
                            || !"TAXABLE".equals(qr.get("outcome")) || !"ZIP".equals(line.addressPrecision())
                            || !quoteExpect.get("internalTaxCode").equals(line.decision().internalTaxCode())
                            || !java.util.Objects.equals(quoteExpect.get("stripeCode"), qr.get("stripeCode"))) {
                        problem = "a taxable line was not priced at state plus local on the items and the delivery";
                    }
                }
                qr.put("pass", problem == null);
                if (problem != null) {
                    failures.add(id + " ZIP " + zip + ": " + problem + " " + qr);
                    ok = false;
                }
                quotes.add(qr);
            }
            r.put("quotes", quotes);
            r.put("pass", ok);
            report.add(r);
        }

        // negatives per category: the first scenario of each category, each required fact removed, text in a number
        List<Map<String, Object>> negatives = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();
        for (Object o : (List<Object>) scenarios.get("scenarios")) {
            Map<String, Object> s = (Map<String, Object>) o;
            String category = (String) s.get("categoryCode");
            if (seen.putIfAbsent(category, true) != null) {
                continue;
            }
            TaxProfile profile = profiles.get(category);
            Map<String, Object> inputs = (Map<String, Object>) s.get("inputs");
            Map<String, Object> neg = new LinkedHashMap<>();
            neg.put("categoryCode", category);
            List<Map<String, Object>> removed = new ArrayList<>();
            List<Map<String, Object>> wrong = new ArrayList<>();
            for (ProfileFact f : profile.facts()) {
                String key = f.id();
                Map<String, Object> givenFacts = new HashMap<>((Map<String, Object>) inputs.get("facts"));
                Map<String, Object> behaviour = new HashMap<>((Map<String, Object>) inputs.get("sellerBehaviour"));
                Map<String, Object> context = new HashMap<>((Map<String, Object>) inputs.get("context"));
                if (f.definition().owner() == com.afrotransact.taxreturns.domain.profile.FactOwner.CONTEXT) {
                    continue;   // worked out by the system, never asked of the seller
                }
                boolean present = givenFacts.containsKey(key) || behaviour.containsKey(key) || context.containsKey(key);
                if (!present) {
                    continue;
                }
                Map<String, Object> f2 = new HashMap<>(givenFacts);
                Map<String, Object> b2 = new HashMap<>(behaviour);
                Map<String, Object> c2 = new HashMap<>(context);
                f2.remove(key);
                b2.remove(key);
                c2.remove(key);
                Resolution gone = resolver.resolve(new ResolveCommand(category, null, f2, b2, c2, "TX", null));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("fact", key);
                row.put("status", gone.status().name());
                String message = gone.missingFacts().stream().filter(m -> m.factId().equals(key)).map(m -> m.message())
                        .findFirst().orElse(null);
                row.put("message", message);
                boolean pass = gone.outcome() == null && "MISSING_FACTS".equals(gone.status().name()) && message != null
                        && message.startsWith(f.definition().label() + " is needed.");
                row.put("pass", pass);
                if (!pass) {
                    failures.add(category + ": without " + key + " -> " + gone.status() + " " + message);
                }
                removed.add(row);

                Object bad = switch (f.definition().kind()) {
                    case DECIMAL, INTEGER, PERCENT -> "abc";
                    case BOOLEAN -> "maybe";
                    case CHOICE -> "no-such-choice";
                    default -> null;
                };
                if (bad != null) {
                    Map<String, Object> f3 = new HashMap<>(givenFacts);
                    Map<String, Object> b3 = new HashMap<>(behaviour);
                    Map<String, Object> c3 = new HashMap<>(context);
                    (givenFacts.containsKey(key) ? f3 : behaviour.containsKey(key) ? b3 : c3).put(key, bad);
                    Resolution wr = resolver.resolve(new ResolveCommand(category, null, f3, b3, c3, "TX", null));
                    Map<String, Object> w = new LinkedHashMap<>();
                    w.put("fact", key);
                    w.put("kind", f.definition().kind() == FactKind.CHOICE ? "CHOICE" : f.definition().kind().name());
                    w.put("status", wr.status().name());
                    boolean wp = "MISSING_FACTS".equals(wr.status().name()) && wr.outcome() == null
                            && wr.missingFacts().stream().anyMatch(m -> m.factId().equals(key));
                    w.put("pass", wp);
                    if (!wp) {
                        failures.add(category + ": " + bad + " for " + key + " gave " + wr.status());
                    }
                    wrong.add(w);
                }
            }
            neg.put("requiredFactRemoved", removed);
            neg.put("wrongType", wrong);
            negatives.add(neg);
        }

        Path out = Path.of("target", "category-tax-matrix");
        Files.createDirectories(out);
        Files.writeString(out.resolve("tax-returns.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                Map.of("scenarios", report, "negatives", negatives, "failures", failures)), StandardCharsets.UTF_8);
        assertThat(failures).as("matrix failures (see target/category-tax-matrix/tax-returns.json)").isEmpty();
        assertThat(report).hasSize(105);
    }

    // ------------------------------------------------------------------ the Texas ZIP table (migration V4)

    private static final Pattern LOCAL = Pattern.compile("\\('TX',\\s*'(\\d{3}|\\d{5})',\\s*'([^']*)',\\s*([0-9.]+),\\s*(true|false)\\)");

    private static TaxRateTable texasTable() {
        try (InputStream in = CategoryTaxMatrixTest.class.getResourceAsStream("/db/migration/V4__texas_comptroller_tax_rates.sql")) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<LocalTaxRate> locals = new ArrayList<>();
            Matcher m = LOCAL.matcher(sql);
            while (m.find()) {
                locals.add(new LocalTaxRate("TX", m.group(1), m.group(2), new BigDecimal(m.group(3)), Boolean.parseBoolean(m.group(4))));
            }
            assertThat(locals).as("the Texas ZIP rows of V4").hasSizeGreaterThan(10);
            StateTaxRule texas = new StateTaxRule("US", "TX", "Texas", new BigDecimal("0.0625"), BigDecimal.ZERO,
                    new BigDecimal("0.0625"), true, true, 0);
            return new TaxRateTable(List.of(texas), locals);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BigDecimal stateRate() {
        return new BigDecimal("0.0625");
    }

    private BigDecimal localRate(String zip) {
        return table.local(new TaxAddress("US", "TX", zip, "Texas")).map(LocalTaxRate::rate).orElse(BigDecimal.ZERO);
    }
}
