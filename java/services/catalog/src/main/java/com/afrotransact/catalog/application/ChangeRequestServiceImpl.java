package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.BusinessDays;
import com.afrotransact.catalog.domain.CatalogPolicy;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.ChangeRequestInvalid;
import com.afrotransact.catalog.domain.ChangeRequestPolicy;
import com.afrotransact.catalog.domain.ChangeRequestStatus;
import com.afrotransact.catalog.domain.ChangeRequestType;
import com.afrotransact.catalog.domain.GtinReader;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductChangeRequest;
import com.afrotransact.catalog.domain.StaleVersionException;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.port.ChangeRequestRepository;
import com.afrotransact.catalog.port.ChangeRequestRepository.Page;
import com.afrotransact.catalog.port.ChangeRequestRepository.Query;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.SellerOffers;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.BusinessRuleException;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ChangeRequestServiceImpl implements ChangeRequestService {

    private final ChangeRequestRepository requests;
    private final ProductRepository products;
    private final SellerOffers sellerOffers;
    private final CatalogSettingsService settings;
    private final ChangeRequestApplier applier;
    private final PageMerger merger;
    private final PageVariantService pageVariants;
    private final ChangeRequestEvents events;
    private final ProductEvents productEvents;
    private final PermissionChecker permissions;
    private final Clock clock;

    ChangeRequestServiceImpl(ChangeRequestRepository requests, ProductRepository products, SellerOffers sellerOffers,
                             CatalogSettingsService settings, ChangeRequestApplier applier, PageMerger merger,
                             PageVariantService pageVariants, ChangeRequestEvents events, ProductEvents productEvents, PermissionChecker permissions,
                             Clock clock) {
        this.requests = requests;
        this.products = products;
        this.sellerOffers = sellerOffers;
        this.settings = settings;
        this.applier = applier;
        this.merger = merger;
        this.pageVariants = pageVariants;
        this.events = events;
        this.productEvents = productEvents;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ChangeRequestView file(String atin, FileCommand cmd) {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        String org = actor.orgId().orElseThrow(() -> new ForbiddenActionException("catalog.no-org",
                "Only seller organisations can ask for a change to a product page"));
        Product page = pageByAtin(atin);
        CatalogPolicy policy = settings.current();
        Map<String, String> errors = ChangeRequestPolicy.problems(cmd.type(), cmd.payload(), cmd.note(),
                policy.noteMaxLength(), page.atin().value());
        String variantAtin = null;
        String gtin14 = null;
        if (cmd.type() == ChangeRequestType.WRONG_MATCH && errors.isEmpty()) {
            variantAtin = ChangeRequestPolicy.text(cmd.payload(), "variantAtin").toUpperCase(Locale.ROOT);
            String wanted = variantAtin;
            if (page.variants().stream().noneMatch(v -> v.atin().value().equals(wanted))) {
                errors = Map.of("payload.variantAtin", "That product is not a variant of this page");
            }
            GtinReader.Reading reading = GtinReader.read(ChangeRequestPolicy.text(cmd.payload(), "gtin"),
                    policy.reservedPrefixes());
            if (!reading.valid()) {
                errors = Map.of("payload.gtin", reading.message());
            } else {
                gtin14 = reading.gtin14();
            }
        }
        if (!errors.isEmpty()) {
            throw new ChangeRequestInvalid("The request does not fit its type", errors);
        }
        boolean owner = page.owner().orgId().equals(org);
        if (cmd.type().needsOffer() && !owner && !sellerOffers.hasOpenOffer(org, page.atin().value())) {
            throw new ForbiddenActionException("catalog.not-your-offer",
                    "Only sellers who sell this product can suggest a change to its page. Add your offer first.");
        }
        String field = ChangeRequestPolicy.fieldOf(cmd.type(), cmd.field(), cmd.payload());
        if (requests.existsOpen(page.id(), cmd.type(), field, org)) {
            throw exists();
        }
        Instant now = Instant.now(clock);
        ProductChangeRequest filed = ProductChangeRequest.filed(page.id(), page.atin().value(), variantAtin, gtin14,
                cmd.type(), field, cmd.payload(), blankToNull(cmd.note()), org, actor.subject(), now,
                BusinessDays.add(now, policy.reviewSlaBusinessDays()));
        ProductChangeRequest saved;
        try {
            saved = requests.insert(filed);
        } catch (DuplicateKeyException ex) {
            throw exists();
        }
        events.created(saved, page, now);
        return view(saved, page, now);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChangeRequestView> forPage(String atin) {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        Product page = pageByAtin(atin);
        boolean all = staff(actor) || actor.orgId().map(page.owner().orgId()::equals).orElse(false);
        String org = all ? null : actor.orgId().orElseThrow(() -> new ForbiddenActionException("catalog.no-org",
                "Only seller organisations can read change requests"));
        Instant now = Instant.now(clock);
        return requests.findByProduct(page.id(), org).stream().map(r -> view(r, page, now)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<ChangeRequestView> mine(ChangeRequestStatus status, int page, int size) {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        String org = actor.orgId().orElseThrow(() -> new ForbiddenActionException("catalog.no-org",
                "Only seller organisations have change requests"));
        return views(requests.search(new Query(status, null, org, false, Math.max(page, 0), Math.clamp(size, 1, 100))));
    }

    @Override
    @Transactional(readOnly = true)
    public ChangeRequestView get(UUID id) {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        ProductChangeRequest r = requests.findById(id).orElseThrow(() -> notFound(id));
        Product page = products.findById(r.productId()).orElseThrow(() -> notFound(id));
        boolean allowed = staff(actor) || actor.orgId().map(o -> o.equals(r.requestedByOrg())
                || o.equals(page.owner().orgId())).orElse(false);
        if (!allowed) {
            throw notFound(id);
        }
        return view(r, page, Instant.now(clock));
    }

    @Override
    @Transactional
    public ChangeRequestView withdraw(UUID id) {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        ProductChangeRequest r = requests.findById(id).orElseThrow(() -> notFound(id));
        if (!actor.orgId().map(r.requestedByOrg()::equals).orElse(false)) {
            throw notFound(id);
        }
        if (!r.open()) {
            throw decidedAlready();
        }
        Instant now = Instant.now(clock);
        ProductChangeRequest saved = stored(r.withdrawn(actor.subject(), now));
        Product page = products.findById(r.productId()).orElseThrow(() -> notFound(id));
        events.decided(saved, page, null, now);
        return view(saved, page, now);
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<ChangeRequestView> queue(ChangeRequestStatus status, ChangeRequestType type, boolean oldestFirst,
                                             int page, int size) {
        permissions.require(CatalogPermissions.MODERATE);
        return views(requests.search(new Query(status, type, null, oldestFirst, Math.max(page, 0),
                Math.clamp(size, 1, 100))));
    }

    @Override
    @Transactional
    public ChangeRequestView decide(UUID id, DecisionCommand cmd) {
        Actor actor = permissions.require(CatalogPermissions.MODERATE);
        ProductChangeRequest r = requests.findById(id).orElseThrow(() -> notFound(id));
        if (!r.open()) {
            throw decidedAlready();
        }
        if (cmd.expectedVersion() != null && cmd.expectedVersion() != r.version()) {
            throw new ConflictException("catalog.concurrent-update",
                    "This request was changed since you opened it; reload and try again");
        }
        String reason = cmd.reason() == null ? "" : cmd.reason().strip();
        if (reason.length() < ProductChangeRequest.MIN_REASON) {
            throw new CatalogRuleViolation("The decision needs a reason", Map.of("reason", "At least "
                    + ProductChangeRequest.MIN_REASON + " characters"));
        }
        Product page = products.findById(r.productId()).orElseThrow(() -> notFound(id));
        Instant now = Instant.now(clock);
        Long pageVersion = null;
        String resultAtin = null;
        ProductChangeRequest decided;
        if (!cmd.approve()) {
            decided = r.rejected(actor.subject(), reason, now);
        } else {
            String resolution = null;
            switch (r.type()) {
                case WRONG_MATCH -> {
                    resolution = resolveWrongMatch(r, page, cmd, actor.subject(), reason, now);
                    pageVersion = products.findById(page.id()).map(Product::version).orElse(null);
                }
                case DUPLICATE_PAGE -> {
                    resolution = merge(r, page, cmd, actor.subject(), reason, now);
                    pageVersion = products.findById(page.id()).map(Product::version).orElse(null);
                }
                case NEW_VARIANT -> {
                    PageVariantService.Added added = pageVariants.addVariant(r, page,
                            flag(cmd.resolutionParams(), "holdPageForTax"), now);
                    resolution = "NEW_VARIANT";
                    pageVersion = added.page().version();
                    resultAtin = added.atin();
                }
                default -> pageVersion = applier.apply(r, page, now).version();
            }
            decided = r.applied(actor.subject(), reason, resolution, now);
        }
        ProductChangeRequest saved = stored(decided);
        events.decided(saved, page, pageVersion, resultAtin, now);
        return view(saved, page, now);
    }

    /**
     * WRONG_MATCH: staff say what was wrong. CORRECT_GTIN (the barcode on the variant was wrong), MOVE_VARIANT (the variant
     * belongs on the page {@code targetAtin}) or SPLIT_VARIANT (the variant is a product of its own).
     */
    private String resolveWrongMatch(ProductChangeRequest r, Product page, DecisionCommand cmd, String by, String reason,
                                     Instant now) {
        String resolution = cmd.resolution();
        if ("MOVE_VARIANT".equals(resolution)) {
            String target = ChangeRequestPolicy.text(cmd.resolutionParams(), "targetAtin").toUpperCase(Locale.ROOT);
            if (target.isEmpty()) {
                throw new CatalogRuleViolation("Say which page the variant belongs on",
                        Map.of("resolutionParams.targetAtin", "The ATIN of the page it moves to"));
            }
            pageVariants.moveVariant(page, r.variantAtin(), pageByAtin(target),
                    flag(cmd.resolutionParams(), "holdPageForTax"), by, reason, r.id(), now);
            return resolution;
        }
        if ("SPLIT_VARIANT".equals(resolution)) {
            pageVariants.splitVariant(page, r.variantAtin(), by, reason, r.id(), now);
            return resolution;
        }
        if (!"CORRECT_GTIN".equals(resolution)) {
            throw new BusinessRuleException("catalog.resolution-not-supported", resolution == null
                    ? "Say how the wrong match is resolved: CORRECT_GTIN, MOVE_VARIANT or SPLIT_VARIANT"
                    : resolution + " is not a way to resolve a wrong match; use CORRECT_GTIN, MOVE_VARIANT or "
                    + "SPLIT_VARIANT, or reject the report");
        }
        GtinReader.Reading reading = GtinReader.read(ChangeRequestPolicy.text(cmd.resolutionParams(), "gtin"),
                settings.current().reservedPrefixes());
        if (!reading.valid()) {
            throw new CatalogRuleViolation("The corrected barcode is not valid",
                    Map.of("resolutionParams.gtin", String.valueOf(reading.message())));
        }
        String variantAtin = r.variantAtin();
        List<Variant> variants = page.variants().stream()
                .map(v -> v.atin().value().equals(variantAtin) ? v.withGtin(reading.digits()) : v).toList();
        Product saved = products.updateWithVariants(page.withVariants(variants).withContent(page.title(),
                page.attributes(), page.images(), page.details(), now));
        productEvents.publish(CatalogEvents.PRODUCT_UPDATED, saved);
        return resolution;
    }

    private static boolean flag(Map<String, Object> params, String key) {
        return params != null && Boolean.parseBoolean(String.valueOf(params.get(key)));
    }

    /** DUPLICATE_PAGE: merge the pages; the survivor is named in the decision (either of the two). */
    private String merge(ProductChangeRequest r, Product page, DecisionCommand cmd, String by, String reason,
                         Instant now) {
        if (!"MERGE".equals(cmd.resolution())) {
            throw new BusinessRuleException("catalog.resolution-not-supported",
                    "A duplicate page is resolved with MERGE, or the report is rejected");
        }
        String other = ChangeRequestPolicy.text(r.payload(), "duplicateOfAtin").toUpperCase(Locale.ROOT);
        Product otherPage = pageByAtin(other);
        String survivorAtin = ChangeRequestPolicy.text(cmd.resolutionParams(), "survivorAtin").toUpperCase(Locale.ROOT);
        if (survivorAtin.isEmpty()) {
            survivorAtin = other;
        }
        if (!survivorAtin.equals(page.atin().value()) && !survivorAtin.equals(otherPage.atin().value())) {
            throw new BusinessRuleException("catalog.merge-invalid",
                    "The page that survives must be one of the two pages in the request");
        }
        boolean pageSurvives = survivorAtin.equals(page.atin().value());
        Product survivor = pageSurvives ? page : otherPage;
        Product loser = pageSurvives ? otherPage : page;
        Map<String, String> variantMap = stringMap(cmd.resolutionParams().get("variantMap"));
        merger.merge(loser, survivor, variantMap, by, reason, r.id(), now);
        return "MERGE";
    }

    private ProductChangeRequest stored(ProductChangeRequest next) {
        try {
            return requests.update(next);
        } catch (StaleVersionException ex) {
            throw decidedAlready();
        }
    }

    private Product pageByAtin(String raw) {
        Atin atin;
        try {
            atin = new Atin(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new NotFoundException("catalog.product-not-found", "No product " + raw);
        }
        return products.findByAtin(atin).or(() -> products.findByVariantAtin(atin))
                .orElseThrow(() -> new NotFoundException("catalog.product-not-found", "No product " + raw));
    }

    private PageView<ChangeRequestView> views(Page page) {
        Instant now = Instant.now(clock);
        List<ChangeRequestView> items = page.items().stream()
                .map(r -> view(r, products.findById(r.productId()).orElse(null), now)).toList();
        return new PageView<>(items, page.total(), page.page(), page.size());
    }

    private static ChangeRequestView view(ProductChangeRequest r, Product page, Instant now) {
        boolean overdue = r.open() && r.slaDueAt() != null && now.isAfter(r.slaDueAt());
        return new ChangeRequestView(r, page == null ? null : page.title(), overdue,
                BusinessDays.hoursBetween(r.requestedAt(), now));
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k).toUpperCase(Locale.ROOT), String.valueOf(v).toUpperCase(Locale.ROOT)));
        return out;
    }

    private static boolean staff(Actor actor) {
        return actor.type() == PrincipalType.STAFF || actor.type() == PrincipalType.SERVICE
                || actor.can(CatalogPermissions.MODERATE);
    }

    private static ConflictException exists() {
        return new ConflictException("catalog.change-request-exists",
                "You already have a request waiting for this. We will tell you when it is decided.");
    }

    private static ConflictException decidedAlready() {
        return new ConflictException("catalog.change-request-decided", "This request was already decided");
    }

    private static NotFoundException notFound(UUID id) {
        return new NotFoundException("catalog.change-request-not-found", "Change request " + id + " not found");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
