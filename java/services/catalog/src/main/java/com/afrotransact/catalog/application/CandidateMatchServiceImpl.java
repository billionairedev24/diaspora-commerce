package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.BusinessDays;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.ChangeRequestStatus;
import com.afrotransact.catalog.domain.ChangeRequestType;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductChangeRequest;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.brand.BrandKeys;
import com.afrotransact.catalog.domain.matching.CandidateRanker;
import com.afrotransact.catalog.domain.matching.CandidateRanker.Kind;
import com.afrotransact.catalog.domain.matching.CandidateRanker.Score;
import com.afrotransact.catalog.domain.matching.MatchThresholds;
import com.afrotransact.catalog.domain.matching.NameTokens;
import com.afrotransact.catalog.domain.matching.PackSize;
import com.afrotransact.catalog.domain.matching.RealPhoto;
import com.afrotransact.catalog.domain.matching.SizeVariantRules;
import com.afrotransact.catalog.port.CandidateRecall;
import com.afrotransact.catalog.port.CandidateRecall.RecallUnavailable;
import com.afrotransact.catalog.port.ChangeRequestRepository;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.MatchDecisionRepository;
import com.afrotransact.catalog.port.MatchDecisionRepository.Decision;
import com.afrotransact.catalog.port.MatchMetrics;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.BusinessRuleException;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CandidateMatchServiceImpl implements CandidateMatchService {

    private static final Logger LOG = LoggerFactory.getLogger(CandidateMatchServiceImpl.class);

    static final int MAX_QUERY = 200;
    private static final int ROW_CANDIDATES = 3;
    private static final List<String> SIZE_ATTRIBUTES = List.of("net_content", "size", "weight", "volume");
    private static final String NONE_STATE = "NONE";
    private static final String OPEN_STATE = "OPEN";

    private final CandidateRecall recall;
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final OfferEligibilityService eligibility;
    private final CatalogSettingsService settings;
    private final MatchDecisionRepository decisions;
    private final SearchRateLimiter limiter;
    private final MatchThresholds thresholds;
    private final ActorResolver actors;
    private final MatchMetrics metrics;
    private final ChangeRequestRepository requests;
    private final ChangeRequestEvents requestEvents;
    private final Clock clock;

    CandidateMatchServiceImpl(CandidateRecall recall, ProductRepository products, CategoryRepository categories,
                              OfferEligibilityService eligibility, CatalogSettingsService settings,
                              MatchDecisionRepository decisions, SearchRateLimiter limiter, MatchThresholds thresholds,
                              ActorResolver actors, MatchMetrics metrics, ChangeRequestRepository requests,
                              ChangeRequestEvents requestEvents, Clock clock) {
        this.recall = recall;
        this.products = products;
        this.categories = categories;
        this.eligibility = eligibility;
        this.settings = settings;
        this.decisions = decisions;
        this.limiter = limiter;
        this.thresholds = thresholds;
        this.actors = actors;
        this.metrics = metrics;
        this.requests = requests;
        this.requestEvents = requestEvents;
        this.clock = clock;
    }

    /** One ranked page, before it is turned into the answer. */
    private record Ranked(Product page, Score score) {
    }

    private record Found(List<Ranked> ranked, boolean unavailable) {
    }

    @Override
    @Transactional(readOnly = true)
    public CandidateResult candidates(CandidateCommand cmd) {
        String org = organisation(cmd.sellerOrgId());
        String name = clean(cmd.query());
        if (letters(name) < thresholds.minQueryLength()) {
            return new CandidateResult(List.of(), null, categoryOf(cmd.categoryId()), true, false);
        }
        if (!limiter.tryAcquire(org, thresholds.searchesPerHour())) {
            throw new BusinessRuleException("catalog.match-rate-limited",
                    "You are searching very fast. Wait a few minutes and try again.");
        }
        Found found = find(name, null, cmd.categoryId());
        List<Ranked> top = found.ranked().stream().limit(thresholds.maxCandidates()).toList();
        List<Candidate> out = new ArrayList<>();
        for (Ranked r : top) {
            out.add(candidate(r, org));
        }
        Category chosen = categoryOf(cmd.categoryId());
        Category suggested = chosen != null ? null : suggest(top);
        return new CandidateResult(out, suggested, chosen, false, found.unavailable());
    }

    @Override
    @Transactional
    public DecisionResult decide(DecisionCommand cmd) {
        String org = organisation(cmd.sellerOrgId());
        String name = clean(cmd.query());
        Found found = find(name, null, cmd.categoryId());
        List<Ranked> top = found.ranked().stream().limit(thresholds.maxCandidates()).toList();
        String shown = top.stream().map(r -> r.page().atin().value() + ":" + r.score().score() + ":" + r.score().kind())
                .collect(Collectors.joining(","));
        Instant now = clock.instant();
        if (cmd.choice() == Choice.NONE_OF_THESE) {
            if (decisions.countNoneSince(org, now.minus(Duration.ofHours(24))) >= thresholds.noneDecisionsPerDay()) {
                throw new BusinessRuleException("catalog.match-rate-limited",
                        "You have added many new products today. Try again tomorrow, or contact support.");
            }
            Optional<Ranked> likely = top.stream().filter(r -> r.score().score() >= thresholds.likelyMin()).findFirst();
            UUID id = UUID.randomUUID();
            decisions.insert(new Decision(id, org, Choice.NONE_OF_THESE.name(), name, cmd.categoryId(), null, shown,
                    likely.map(r -> r.page().atin().value()).orElse(null), likely.isPresent() ? OPEN_STATE : NONE_STATE,
                    now));
            return new DecisionResult(id, Choice.NONE_OF_THESE, null, false, List.of(), likely.isPresent());
        }
        if (cmd.atin() == null || cmd.atin().isBlank()) {
            throw new BusinessRuleException("catalog.match-choice-missing", "Choose the product you sell");
        }
        Ranked chosen = null;
        Variant variant = null;
        for (Ranked r : top) {
            for (Variant v : r.page().variants()) {
                if (v.atin().value().equals(cmd.atin().strip().toUpperCase(Locale.ROOT))) {
                    chosen = r;
                    variant = v;
                }
            }
        }
        if (chosen == null) {
            throw new BusinessRuleException("catalog.match-choice-not-shown",
                    "That product was not in the list. Search again and choose from the list.");
        }
        if (sizeLabel(chosen.page()) != null && !cmd.sizeConfirmed()) {
            throw new BusinessRuleException("catalog.match-size-unconfirmed",
                    "Tell us the size is the same as on this page, or ask to add your own size to it.");
        }
        OfferEligibilityService.Eligibility e = eligibility.evaluate(chosen.page(), variant, org);
        UUID id = UUID.randomUUID();
        decisions.insert(new Decision(id, org, Choice.SELL_EXISTING.name(), name, cmd.categoryId(), variant.atin().value(),
                shown, null, NONE_STATE, now));
        return new DecisionResult(id, Choice.SELL_EXISTING, variant.atin().value(), e.canOffer(), e.blockers(), false);
    }

    @Override
    @Transactional
    public SizeRequestResult requestSize(SizeRequestCommand cmd) {
        Actor actor = actors.current();
        String org = organisation(cmd.sellerOrgId());
        Found found = find(clean(cmd.query()), null, cmd.categoryId());
        String wanted = cmd.atin() == null ? "" : cmd.atin().strip().toUpperCase(Locale.ROOT);
        Product page = found.ranked().stream().limit(thresholds.maxCandidates()).map(Ranked::page)
                .filter(p -> p.atin().value().equals(wanted)
                        || p.variants().stream().anyMatch(v -> v.atin().value().equals(wanted)))
                .findFirst().orElseThrow(() -> new BusinessRuleException("catalog.match-choice-not-shown",
                        "That product was not in the list. Search again and choose from the list."));
        Variant first = page.variants().getFirst();
        OfferEligibilityService.Eligibility e = eligibility.evaluate(page, first, org);
        if (!e.canOffer()) {
            throw new BusinessRuleException("catalog.variant-request-blocked", e.blockers().isEmpty()
                    ? "You cannot sell on this page yet." : e.blockers().getFirst().message());
        }
        SizeVariantRules.Outcome outcome = SizeVariantRules.decide(cmd.size(), pageSizeText(page), page.variationTheme(),
                page.variants().stream().map(v -> new SizeVariantRules.PageVariant(v.atin().value(), v.options())).toList());
        switch (outcome.kind()) {
            case SIZE_INVALID -> throw new BusinessRuleException("catalog.variant-size-invalid",
                    "Type the size with its unit, like 1 kg, 500 ml or 6 pack.");
            case PAGE_SIZE_UNKNOWN -> throw new BusinessRuleException("catalog.variant-page-size-unknown",
                    "This page does not state a size, so we cannot tell what is different. Choose This is my product, or "
                            + "add it as a new product.");
            case THEME_UNSUPPORTED -> throw new BusinessRuleException("catalog.variant-theme-unsupported",
                    "This page has other options, like colour, so your size cannot be added to it here. Add it as a new "
                            + "product instead.");
            case ALREADY_ON_PAGE -> {
                Variant existing = page.variants().stream().filter(v -> v.atin().value().equals(outcome.existingAtin()))
                        .findFirst().orElse(first);
                OfferEligibilityService.Eligibility ex = eligibility.evaluate(page, existing, org);
                return new SizeRequestResult(SizeRequestOutcome.ALREADY_ON_PAGE, outcome.label(), null,
                        existing.atin().value(), null, ex.canOffer(), ex.blockers());
            }
            default -> {
            }
        }
        Optional<ProductChangeRequest> waiting = requests.findByProduct(page.id(), org).stream()
                .filter(r -> r.type() == ChangeRequestType.NEW_VARIANT && r.open() && outcome.key().equals(r.field()))
                .findFirst();
        if (waiting.isPresent()) {
            return new SizeRequestResult(SizeRequestOutcome.ALREADY_REQUESTED, outcome.label(), waiting.get().id(), null,
                    waiting.get().slaDueAt(), false, List.of());
        }
        long open = requests.search(new ChangeRequestRepository.Query(ChangeRequestStatus.PENDING,
                ChangeRequestType.NEW_VARIANT, false, org, false, 0, 1)).total();
        if (open >= thresholds.openSizeRequests()) {
            throw new BusinessRuleException("catalog.match-rate-limited", "You have many sizes waiting for review. We will "
                    + "answer them first; you can also withdraw one you do not need.");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", PageVariantServiceImpl.SELLER_SIZE);
        payload.put("options", Map.of(outcome.dimension(), outcome.label()));
        if (outcome.pageLabel() != null) {
            payload.put("existingOptions", Map.of(outcome.dimension(), outcome.pageLabel()));
        }
        Instant now = clock.instant();
        ProductChangeRequest filed = ProductChangeRequest.filed(page.id(), page.atin().value(), null, null,
                ChangeRequestType.NEW_VARIANT, outcome.key(), payload, blankToNull(cmd.note()), org, actor.subject(), now,
                BusinessDays.add(now, settings.current().reviewSlaBusinessDays()));
        ProductChangeRequest saved;
        try {
            saved = requests.insert(filed);
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("catalog.change-request-exists",
                    "You already have a request waiting for this size. We will tell you when it is decided.");
        }
        requestEvents.created(saved, page, now);
        return new SizeRequestResult(SizeRequestOutcome.REQUESTED, outcome.label(), saved.id(), null, saved.slaDueAt(),
                false, List.of());
    }

    @Override
    @Transactional(readOnly = true)
    public RowMatch matchRow(String sellerOrgId, String name, String brand, UUID categoryId) {
        String org = sellerOrgId == null || sellerOrgId.isBlank() ? null : sellerOrgId.strip();
        String cleaned = clean(name);
        if (letters(cleaned) < thresholds.minQueryLength()) {
            return new RowMatch(RowKind.NONE, null, null, null, false, List.of(), List.of());
        }
        List<Ranked> ranked = find(cleaned, brand, categoryId).ranked();
        List<Ranked> exact = ranked.stream().filter(r -> r.score().kind() == Kind.SAME_NAME).toList();
        if (exact.size() == 1 && exact.getFirst().page().variants().size() == 1) {
            Ranked r = exact.getFirst();
            Variant v = r.page().variants().getFirst();
            OfferEligibilityService.Eligibility e = eligibility.evaluate(r.page(), v, org);
            return new RowMatch(RowKind.LINKED, v.atin().value(), r.page().atin().value(), r.page().title(),
                    e.canOffer(), e.blockers(), List.of());
        }
        List<Candidate> held = ranked.stream().limit(ROW_CANDIDATES).map(r -> candidate(r, org)).toList();
        return held.isEmpty() ? new RowMatch(RowKind.NONE, null, null, null, false, List.of(), List.of())
                : new RowMatch(RowKind.HELD, null, null, null, false, List.of(), held);
    }

    private String organisation(String requested) {
        Actor actor = actors.current();
        if (!actor.can(CatalogPermissions.READ) && !actor.can(CatalogPermissions.WRITE)) {
            throw new ForbiddenActionException("auth.missing-permission",
                    "Missing one of the permissions " + CatalogPermissions.READ + ", " + CatalogPermissions.WRITE);
        }
        boolean trusted = actor.type() == PrincipalType.STAFF || actor.type() == PrincipalType.SERVICE;
        String org = trusted ? requested : actor.orgId().orElse(null);
        if (org == null || org.isBlank()) {
            throw new BusinessRuleException("catalog.match-org-missing", "Say which store is asking");
        }
        return org.strip();
    }

    /**
     * Recalls from the search index, falling back to the catalogue's own tables when the index cannot be asked or knows
     * nothing (an empty or not yet built index must never stop a seller from finding the page); keeps what a seller may be
     * shown and ranks it. Best first, ties by ATIN. Unavailable only when both ways fail.
     */
    private Found find(String name, String brand, UUID categoryId) {
        CategoryNode chosen = categoryId == null ? null : categories.findById(categoryId).orElse(null);
        CandidateRanker.Query query = new CandidateRanker.Query(name, brand, categoryId, chosen == null ? null : chosen.path());
        String text = brand == null || brand.isBlank() ? name : brand + " " + name;
        List<Product> pages = List.of();
        String reason = null;
        try {
            List<String> atins = recall.recall(text, thresholds.recall());
            if (atins.isEmpty()) {
                reason = "search_empty";
            } else {
                pages = pagesOf(atins);
            }
        } catch (RecallUnavailable e) {
            reason = "search_error";
            LOG.warn("Candidate search: the search index could not be asked ({}); answering from the catalogue database",
                    causeOf(e));
        }
        if (reason != null) {
            try {
                pages = fromDatabase(text);
                metrics.candidatesFallback(reason);
                if (reason.equals("search_empty")) {
                    LOG.debug("Candidate search: the search index knew nothing, the database found {} page(s)", pages.size());
                }
            } catch (RuntimeException e) {
                metrics.candidatesFallback("both_failed");
                LOG.warn("Candidate search: the search index ({}) and the database ({}) both failed", reason, causeOf(e));
                return new Found(List.of(), true);
            }
        }
        String partKey = settings.current().mpnAttributeKey();
        Map<UUID, Ranked> byPage = new LinkedHashMap<>();
        for (Product p : pages) {
            if (!shown(p)) {
                continue;
            }
            CategoryNode node = p.categoryId() == null ? null : categories.findById(p.categoryId()).orElse(null);
            CandidateRanker.Page view = new CandidateRanker.Page(p.atin().value(), p.title(),
                    p.attributes().get(BrandKeys.NAME), p.categoryId(), node == null ? null : node.path(),
                    sizeText(p), p.attributes().get(partKey));
            Score score = CandidateRanker.score(query, view, thresholds);
            if (score.score() >= thresholds.showMin()) {
                byPage.putIfAbsent(p.id(), new Ranked(p, score));
            }
        }
        List<Ranked> ranked = byPage.values().stream()
                .sorted(Comparator.comparingInt((Ranked r) -> r.score().score()).reversed()
                        .thenComparing(r -> r.page().atin().value()))
                .toList();
        return new Found(ranked, false);
    }

    /** The pages behind the index's ATINs, in the index's order; an ATIN catalog does not know is dropped. */
    private List<Product> pagesOf(List<String> atins) {
        List<Product> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String atin : atins) {
            if (seen.add(atin)) {
                productOf(atin).ifPresent(out::add);
            }
        }
        return out;
    }

    /** The deterministic match on the words of the name (a barcode has its own exact lookup, CatalogMatchService). */
    private List<Product> fromDatabase(String text) {
        return products.findLiveByNameWords(NameTokens.of(text), thresholds.recall());
    }

    /** The class of the failure, never its message (a message can carry what the seller typed). */
    private static String causeOf(Throwable e) {
        Throwable root = e.getCause() == null ? e : e.getCause();
        return root.getClass().getSimpleName();
    }

    private Optional<Product> productOf(String atin) {
        try {
            return products.findByAtin(new Atin(atin));
        } catch (IllegalArgumentException notAnAtin) {
            return Optional.empty();
        }
    }

    /** Only live pages with a real photo and at least one variant: nobody can tell a page without a picture is theirs. */
    private static boolean shown(Product p) {
        return p.status() == ProductStatus.LIVE && !p.variants().isEmpty() && firstImage(p) != null
                && RealPhoto.is(firstImage(p).url());
    }

    private static ProductImage firstImage(Product p) {
        return p.images().stream().filter(i -> i.role() == null && !i.isHeld()).findFirst().orElse(null);
    }

    private Candidate candidate(Ranked r, String org) {
        Product p = r.page();
        Variant first = p.variants().getFirst();
        OfferEligibilityService.Eligibility e = eligibility.evaluate(p, first, org);
        CategoryNode node = p.categoryId() == null ? null : categories.findById(p.categoryId()).orElse(null);
        ProductImage image = firstImage(p);
        List<CandidateVariant> variants = p.variants().stream()
                .map(v -> new CandidateVariant(v.atin().value(), v.options())).toList();
        return new Candidate(p.atin().value(), p.variants().size() == 1 ? first.atin().value() : null, p.title(),
                p.attributes().get(BrandKeys.NAME), new CandidateImage(image.url(), image.width(), image.height()),
                node == null ? null : node.path(), sizeLabel(p), variants, r.score().kind(),
                r.score().score() >= thresholds.likelyMin(), e.canOffer(), e.blockers(), e.allowedConditions());
    }

    /** The size the page states in its own words (size attribute, else the title); empty when it states none. */
    private static String pageSizeText(Product p) {
        String text = sizeText(p);
        return text != null ? text : p.title();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private Category suggest(List<Ranked> top) {
        Map<UUID, Long> votes = top.stream().limit(3).map(r -> r.page().categoryId()).filter(Objects::nonNull)
                .collect(Collectors.groupingBy(id -> id, LinkedHashMap::new, Collectors.counting()));
        Optional<UUID> best = votes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey);
        return best.map(this::categoryOf).orElse(null);
    }

    private Category categoryOf(UUID id) {
        if (id == null) {
            return null;
        }
        return categories.findById(id).map(n -> new Category(n.id(), n.path())).orElse(null);
    }

    private static String sizeText(Product p) {
        for (String key : SIZE_ATTRIBUTES) {
            String v = p.attributes().get(key);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** The size the one-tap question names; null when the page states none. */
    private static String sizeLabel(Product p) {
        String text = sizeText(p);
        return PackSize.find(text != null ? text : p.title()).or(() -> PackSize.find(p.title()))
                .map(f -> f.size().label()).orElse(null);
    }

    private static String clean(String text) {
        String t = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        return t.length() > MAX_QUERY ? t.substring(0, MAX_QUERY) : t;
    }

    private static int letters(String text) {
        return (int) text.chars().filter(Character::isLetter).count();
    }
}
