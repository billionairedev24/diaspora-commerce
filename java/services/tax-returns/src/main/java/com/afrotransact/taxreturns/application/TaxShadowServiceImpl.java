package com.afrotransact.taxreturns.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.shadow.ShadowComparator;
import com.afrotransact.taxreturns.domain.shadow.ShadowFailure;
import com.afrotransact.taxreturns.domain.shadow.ShadowRow;
import com.afrotransact.taxreturns.domain.tax.TaxLineInput;
import com.afrotransact.taxreturns.domain.tax.TaxLineResult;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import com.afrotransact.taxreturns.port.InHouseTaxCalculator;
import com.afrotransact.taxreturns.port.ProviderTaxCalculator;
import com.afrotransact.taxreturns.port.ShadowQuoteRepository;
import com.afrotransact.taxreturns.port.TaxProviderException;
import com.afrotransact.taxreturns.port.TaxTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TaxShadowServiceImpl implements TaxShadowService, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TaxShadowServiceImpl.class);

    private final InHouseTaxCalculator inHouse;
    private final List<ProviderTaxCalculator> providers;
    private final ShadowQuoteRepository repository;
    private final TaxTelemetry telemetry;
    private final TaxReturnsProperties props;
    private final Access access;
    private final Clock clock;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger pending = new AtomicInteger();

    TaxShadowServiceImpl(InHouseTaxCalculator inHouse, List<ProviderTaxCalculator> providers,
                         ShadowQuoteRepository repository, TaxTelemetry telemetry, TaxReturnsProperties props,
                         Access access, Clock clock) {
        this.inHouse = inHouse;
        this.providers = providers;
        this.repository = repository;
        this.telemetry = telemetry;
        this.props = props;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public void compare(Compare request) {
        if (pending.incrementAndGet() > props.tax().shadow().maxPending()) {
            pending.decrementAndGet();
            telemetry.shadowFailure(request.provider(), "overloaded");
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    run(request);
                } catch (RuntimeException ex) {
                    LOG.warn("Shadow comparison with {} failed: {}", request.provider(), ex.toString());
                    telemetry.shadowFailure(request.provider(), "error");
                } finally {
                    pending.decrementAndGet();
                }
            });
        } catch (RuntimeException ex) {
            pending.decrementAndGet();
            telemetry.shadowFailure(request.provider(), "rejected");
        }
    }

    /** Runs one comparison to the end (called on a worker thread; package-private for tests). */
    void run(Compare c) {
        Instant at = clock.instant();
        String state = c.shipTo().state();
        List<ShadowRow> rows = new ArrayList<>();
        List<TaxShadowService.Line> decided = new ArrayList<>();
        for (TaxShadowService.Line l : c.lines()) {
            if (l.decision().isBlocked()) {
                TaxLineInput in = l.input();
                rows.add(ShadowComparator.noDecision(at, c.reference(), state, l.decision().categoryCode(), c.currency(),
                        c.provider(), in.lineId(), in.atin()));
            } else {
                decided.add(l);
            }
        }
        if (!decided.isEmpty()) {
            rows.addAll(compareDecided(c, decided, at, state));
        }
        repository.saveAll(rows);
        rows.forEach(r -> telemetry.shadow(c.provider(), r.kind()));
    }

    /** Our quote for every decided line, then the provider's for the lines that carry its code (never a guess). */
    private List<ShadowRow> compareDecided(Compare c, List<TaxShadowService.Line> decided, Instant at, String state) {
        List<TaxLineInput> taxable = decided.stream().map(TaxShadowService.Line::input).filter(i -> !i.exempt()).toList();
        Map<String, TaxLineResult> ours = results(taxable.isEmpty() ? null : inHouse.quote(c.shipTo(), c.currency(), taxable));
        List<TaxShadowService.Line> coded = decided.stream().filter(l -> l.input().codeFor(c.provider()) != null)
                .toList();
        List<ShadowRow> rows = new ArrayList<>();
        for (TaxShadowService.Line l : decided) {
            TaxLineInput in = l.input();
            if (in.codeFor(c.provider()) == null) {
                rows.add(ShadowComparator.noCode(at, c.reference(), state, l.decision().categoryCode(), c.currency(),
                        c.provider(), in, ours.get(in.lineId()), "No " + label(c.provider()) + " code is mapped for "
                        + in.taxCode()));
            }
        }
        if (!coded.isEmpty()) {
            rows.addAll(compareCoded(c, coded, ours, at, state));
        }
        return rows;
    }

    private List<ShadowRow> compareCoded(Compare c, List<TaxShadowService.Line> coded,
                                         Map<String, TaxLineResult> ours, Instant at, String state) {
        List<TaxLineInput> all = coded.stream().map(TaxShadowService.Line::input).toList();
        Map<String, TaxLineResult> theirs = null;
        String error = null;
        ShadowFailure failure = null;
        String name = label(c.provider());
        ProviderTaxCalculator provider = providers.stream().filter(p -> p.provider() == c.provider()).findFirst()
                .orElse(null);
        if (provider == null || !provider.configured()) {
            error = name + " is not configured: its credentials are not set, so it was not asked";
            failure = ShadowFailure.NOT_CONFIGURED;
        } else {
            try {
                TaxQuote q = CompletableFuture.supplyAsync(() -> provider.quoteStrict(c.shipTo(), c.currency(), all),
                        executor).orTimeout(props.tax().shadow().timeout().toMillis(), TimeUnit.MILLISECONDS).join();
                theirs = results(q);
            } catch (CompletionException ex) {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                boolean timeout = cause instanceof TimeoutException;
                error = timeout ? name + " did not answer within " + props.tax().shadow().timeout()
                        : cause instanceof TaxProviderException ? cause.getMessage() : name + " failed: " + cause;
                failure = timeout ? ShadowFailure.TIMEOUT : ShadowFailure.ERROR;
                telemetry.shadowFailure(c.provider(), timeout ? "timeout" : "error");
            }
        }
        List<ShadowRow> rows = new ArrayList<>();
        for (TaxShadowService.Line l : coded) {
            TaxLineInput in = l.input();
            TaxLineResult theirResult = theirs == null ? null : theirs.get(in.lineId());
            String why = theirs == null ? error : theirResult == null ? name + " returned no result for the line" : null;
            ShadowFailure how = theirs == null ? failure : theirResult == null ? ShadowFailure.ERROR : null;
            rows.add(ShadowComparator.compare(at, c.reference(), state, l.decision().categoryCode(), c.currency(),
                    c.provider(), in, ours.get(in.lineId()), theirResult, why, how));
        }
        return rows;
    }

    private static String label(TaxProviderId id) {
        return id == TaxProviderId.STRIPE ? "Stripe Tax" : "Avalara";
    }

    private static Map<String, TaxLineResult> results(TaxQuote q) {
        Map<String, TaxLineResult> out = new HashMap<>();
        if (q != null) {
            q.lines().forEach(r -> out.put(r.lineId(), r));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Report report(LocalDate from, LocalDate to, TaxProviderId provider, boolean onlyDifferences, int page,
                         int size) {
        access.staff(TaxReturnsPermissions.TAX_READ);
        Instant start = from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return new Report(from, to, provider, repository.totals(start, end, provider),
                repository.groups(ShadowQuoteRepository.Dimension.INTERNAL_TAX_CODE, start, end, provider),
                repository.groups(ShadowQuoteRepository.Dimension.CATEGORY, start, end, provider),
                repository.groups(ShadowQuoteRepository.Dimension.OUTCOME, start, end, provider),
                repository.groups(ShadowQuoteRepository.Dimension.STATE, start, end, provider),
                repository.groups(ShadowQuoteRepository.Dimension.KIND, start, end, provider),
                repository.rows(start, end, provider, onlyDifferences, page, size), page, size,
                props.tax().shadow().thresholds().asMap());
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
