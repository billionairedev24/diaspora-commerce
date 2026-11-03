package com.afrotransact.catalog.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.catalog.application.CategoryService;
import com.afrotransact.catalog.application.CreateProductCommand;
import com.afrotransact.catalog.application.PageView;
import com.afrotransact.catalog.application.ProductReviewService;
import com.afrotransact.catalog.application.ProductReviewService.MyReview;
import com.afrotransact.catalog.application.ProductReviewService.PublicReviews;
import com.afrotransact.catalog.application.ProductReviewService.ReportResult;
import com.afrotransact.catalog.application.ProductService;
import com.afrotransact.catalog.application.ReviewModerationService;
import com.afrotransact.catalog.application.SellerReviewService;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductReview;
import com.afrotransact.catalog.domain.PurchaseProof;
import com.afrotransact.catalog.domain.ReviewDraft;
import com.afrotransact.catalog.domain.ReviewPhoto;
import com.afrotransact.catalog.domain.ReviewReasonCode;
import com.afrotransact.catalog.domain.ReviewSort;
import com.afrotransact.catalog.domain.ReviewStateConflict;
import com.afrotransact.catalog.domain.ReviewStatus;
import com.afrotransact.catalog.domain.VariantDraft;
import com.afrotransact.catalog.port.PurchaseVerifier;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.PostgresTestContainer;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S324 verified-purchase product reviews end to end on PostgreSQL: eligibility through orders (faked), one review per
 * customer, the automatic checks, moderation, the 30-day edit window, delete, reports, the seller's single reply,
 * the per-product rating and the events that carry it to search and trust-ops.
 * {@code AT_IT_POSTGRES_URL=jdbc:postgresql://…} runs the tests on that PostgreSQL instead of a container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false", "at.consumers.enabled=false", "spring.kafka.bootstrap-servers=localhost:1",
    "at.catalog.reviews.photo-hosts=utfs.io,media.example"})
@ActiveProfiles("local")
@Import(ProductReviewIT.Fakes.class)
class ProductReviewIT {

    static final String EXTERNAL_DB = System.getenv("AT_IT_POSTGRES_URL");
    static final PostgreSQLContainer POSTGRES = EXTERNAL_DB == null ? PostgresTestContainer.create() : null;

    static {
        if (POSTGRES != null) {
            POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> EXTERNAL_DB != null ? EXTERNAL_DB : POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_USER", "postgres") : POSTGRES.getUsername());
        registry.add("spring.datasource.password", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_PASSWORD", "") : POSTGRES.getPassword());
    }

    private static final String RETAIL_ORG = "org_afrotransact_retail";
    private static final ProductImage IMAGE = new ProductImage("https://utfs.io/f/ankara.jpg", 1600, 1600, 300_000,
            "image/jpeg");
    private static final ReviewPhoto PHOTO = new ReviewPhoto("https://utfs.io/f/review-1.jpg");
    private static final Actor ADMIN = new Actor("local-dev", PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.super_admin"), Set.of("*"));
    private static final AtomicReference<Actor> CURRENT = new AtomicReference<>(ADMIN);
    private static final Map<String, PurchaseProof> DELIVERED = new ConcurrentHashMap<>();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** A switchable actor for direct use-case calls and a fake orders-service. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {

        @Bean
        ActorResolver switchableActorResolver() {
            return CURRENT::get;
        }

        @Bean
        @Primary
        PurchaseVerifier fakeOrders() {
            return (customerId, atin) -> Optional.ofNullable(DELIVERED.get(customerId + "|" + atin));
        }
    }

    private final ProductService products;
    private final CategoryService categories;
    private final ProductReviewService reviews;
    private final SellerReviewService seller;
    private final ReviewModerationService moderation;
    private final JdbcClient jdbc;
    private final int port;

    @Autowired
    ProductReviewIT(ProductService products, CategoryService categories, ProductReviewService reviews,
                    SellerReviewService seller, ReviewModerationService moderation, JdbcClient jdbc,
                    @LocalServerPort int port) {
        this.products = products;
        this.categories = categories;
        this.reviews = reviews;
        this.seller = seller;
        this.moderation = moderation;
        this.jdbc = jdbc;
        this.port = port;
    }

    @AfterEach
    void reset() {
        CURRENT.set(ADMIN);
    }

    // ------------------------------------------------------------------ fixtures

    private static Actor customer(String id) {
        return new Actor(id, PrincipalType.CUSTOMER, Optional.empty(), Set.of("account.customer"), Set.of());
    }

    private static Actor sellerMember(String org) {
        return new Actor("member-" + org, PrincipalType.SELLER_MEMBER, Optional.of(org), Set.of("seller.owner"),
                Set.of("catalog:write"));
    }

    private static String cust() {
        return "cust-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Product liveProduct() {
        CategoryNode dresses = categories.tree().stream()
                .filter(n -> n.path().equals("Fashion › Women › Dresses")).findFirst().orElseThrow();
        String sku = "S" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        Product created = products.create(new CreateProductCommand(dresses.id(), null,
                "Ankara Wrap Dress " + sku,
                Map.of("brand", "Lagos Textile Hub", "material", "Cotton", "fit", "Regular", "length", "Midi",
                        "sleeve", "Short", "occasion", "Wedding / owambe", "care", "Cold wash", "origin", "Nigeria"),
                List.of(IMAGE), "Size × Color",
                List.of(new VariantDraft(Map.of("Size", "M", "Color", "Gold"), sku, null, true, 4999, 3)), "USD"));
        return products.approve(created.id());
    }

    private static void delivered(String customer, Product p) {
        DELIVERED.put(customer + "|" + p.atin().value(), new PurchaseProof("order-" + customer,
                "AT-2026-" + customer.substring(5).toUpperCase(Locale.ROOT), null));
    }

    private static ReviewDraft draft(int stars, String text) {
        return new ReviewDraft(stars, "Worth it", text, List.of(PHOTO));
    }

    /** A customer who received the product writes a clean review. */
    private ProductReview reviewed(Product p, String customer, String name, int stars, String text) {
        delivered(customer, p);
        CURRENT.set(customer(customer));
        ProductReview r = reviews.write(p.atin().value(), name, draft(stars, text));
        CURRENT.set(ADMIN);
        return r;
    }

    private JsonNode payload(String type, String aggregateId) {
        return JSON.readTree(jdbc.sql("select payload::text from platform_outbox where type = :t and aggregate_id = :id "
                        + "order by occurred_at desc, id desc limit 1")
                .param("t", type).param("id", aggregateId).query(String.class).single());
    }

    private long count(String type, UUID aggregateId) {
        return jdbc.sql("select count(*) from platform_outbox where type = :t and aggregate_id = :id")
                .param("t", type).param("id", aggregateId.toString()).query(Long.class).single();
    }

    // ------------------------------------------------------------------ eligibility and writing

    @Test
    void onlyACustomerWhoReceivedTheProductCanReviewItAndOnlyOnce() {
        Product p = liveProduct();
        String atin = p.atin().value();
        String ada = cust();
        CURRENT.set(customer(ada));

        MyReview before = reviews.mine(atin);
        assertThat(before.eligible()).isFalse();
        assertThat(before.reason()).isEqualTo("not-purchased");
        assertThat(before.review()).isNull();
        assertThatThrownBy(() -> reviews.write(atin, "Adaeze Nwosu", draft(5, "Lovely")))
                .isInstanceOf(ForbiddenActionException.class)
                .extracting(e -> ((ForbiddenActionException) e).code()).isEqualTo("catalog.review-not-verified-purchase");

        delivered(ada, p);
        assertThat(reviews.mine(atin).eligible()).isTrue();
        ProductReview written = reviews.write(atin, "Adaeze Nwosu", draft(5, "Lovely fit and the colour is true"));
        assertThat(written.status()).isEqualTo(ReviewStatus.PUBLISHED);
        assertThat(written.reviewerName()).isEqualTo("Adaeze N.");
        assertThat(written.orderNumber()).startsWith("AT-2026-");

        MyReview after = reviews.mine(atin);
        assertThat(after.eligible()).isFalse();
        assertThat(after.reason()).isEqualTo("already-reviewed");
        assertThat(after.review().id()).isEqualTo(written.id());
        assertThat(after.editable()).isTrue();
        assertThatThrownBy(() -> reviews.write(atin, "Adaeze Nwosu", draft(1, "Again")))
                .isInstanceOf(ReviewStateConflict.class)
                .extracting(e -> ((ReviewStateConflict) e).code()).isEqualTo("catalog.review-already-exists");

        // someone who bought another product cannot review this one
        CURRENT.set(customer(cust()));
        assertThatThrownBy(() -> reviews.write(atin, "Bola A", draft(4, "Not mine")))
                .isInstanceOf(ForbiddenActionException.class);
        // staff, sellers and anonymous callers are not shoppers
        CURRENT.set(ADMIN);
        assertThatThrownBy(() -> reviews.write(atin, "Staff", draft(4, "x"))).isInstanceOf(ForbiddenActionException.class)
                .extracting(e -> ((ForbiddenActionException) e).code()).isEqualTo("catalog.customer-only");
        CURRENT.set(sellerMember(RETAIL_ORG));
        assertThatThrownBy(() -> reviews.mine(atin)).isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void aPausedOrUnknownProductCannotBeReviewedButAPausedOneCan() {
        Product p = liveProduct();
        String ada = cust();
        delivered(ada, p);
        CURRENT.set(customer(ada));
        assertThatThrownBy(() -> reviews.write("AT00000000", "Ada O", draft(5, "x"))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> reviews.write("nonsense", "Ada O", draft(5, "x"))).isInstanceOf(NotFoundException.class);

        CURRENT.set(ADMIN);
        products.pause(p.id());
        CURRENT.set(customer(ada));
        assertThat(reviews.write(p.atin().value(), "Ada O", draft(5, "Paused but received")).status())
                .isEqualTo(ReviewStatus.PUBLISHED);
    }

    @Test
    void validationAndPhotoRulesGiveFieldErrors() {
        Product p = liveProduct();
        String atin = p.atin().value();
        String ada = cust();
        delivered(ada, p);
        CURRENT.set(customer(ada));
        assertThatThrownBy(() -> reviews.write(atin, "Ada O", new ReviewDraft(0, null, null, null)))
                .isInstanceOf(CatalogRuleViolation.class)
                .satisfies(e -> assertThat(((CatalogRuleViolation) e).fieldErrors()).containsKey("rating"));
        assertThatThrownBy(() -> reviews.write(atin, "Ada O", new ReviewDraft(5, null, null,
                List.of(new ReviewPhoto("https://evil.example/pixel.gif")))))
                .isInstanceOf(CatalogRuleViolation.class)
                .satisfies(e -> assertThat(((CatalogRuleViolation) e).fieldErrors()).containsKey("photos"));
        // nothing was stored by the failed attempts
        assertThat(reviews.mine(atin).eligible()).isTrue();
        ProductReview ok = reviews.write(atin, "Ada O", new ReviewDraft(4, null, null, List.of(PHOTO, PHOTO)));
        assertThat(ok.content().photos()).hasSize(2);
        assertThat(ok.content().title()).isNull();
    }

    // ------------------------------------------------------------------ rating and events

    @Test
    void publishedReviewsMaintainTheRatingAndTheEventsSearchNeeds() {
        Product p = liveProduct();
        String atin = p.atin().value();
        assertThat(reviews.rating(atin).count()).isZero();

        reviewed(p, cust(), "Ada Obi", 5, "Perfect");
        reviewed(p, cust(), "Bola Ade", 4, "Good");
        reviewed(p, cust(), "Chi Eze", 4, "Good too");

        var rating = reviews.rating(atin);
        assertThat(rating.count()).isEqualTo(3);
        assertThat(rating.average()).isEqualByComparingTo("4.33");
        assertThat(rating.breakdown()).containsExactly(0, 0, 0, 2, 1);
        assertThat(jdbc.sql("select review_count from product_rating where product_id = :p").param("p", p.id())
                .query(Integer.class).single()).isEqualTo(3);

        JsonNode changed = payload("catalog.product.rating_changed", p.id().toString());
        assertThat(changed.path("atin").asString()).isEqualTo(atin);
        assertThat(changed.path("count").asInt()).isEqualTo(3);
        assertThat(changed.path("average").asDouble()).isEqualTo(4.33);
        assertThat(changed.path("breakdown")).hasSize(5);
        assertThat(count("catalog.product.rating_changed", p.id())).isEqualTo(3);
        // the same aggregate again publishes nothing
        CURRENT.set(ADMIN);
        assertThat(count("catalog.product.rating_changed", p.id())).isEqualTo(3);
    }

    // ------------------------------------------------------------------ automatic checks and moderation

    @Test
    void aFlaggedReviewWaitsForModerationAndIsCountedOnlyOnceApproved() {
        Product p = liveProduct();
        ProductReview ok = reviewed(p, cust(), "Ada Obi", 5, "Fine");
        String bola = cust();
        ProductReview flagged = reviewed(p, bola, "Bola Ade", 1, "Awful, call me on 0803 123 4567 or www.deals.shop");

        assertThat(ok.status()).isEqualTo(ReviewStatus.PUBLISHED);
        assertThat(flagged.status()).isEqualTo(ReviewStatus.PENDING);
        assertThat(flagged.flags()).hasSize(2);
        assertThat(reviews.rating(p.atin().value()).count()).isEqualTo(1);
        JsonNode submitted = payload("catalog.review.submitted", flagged.id().toString());
        assertThat(submitted.path("contentType").asString()).isEqualTo("PRODUCT_REVIEW");
        assertThat(submitted.path("reviewerName").asString()).isEqualTo("Bola A.");
        assertThat(submitted.path("status").asString()).isEqualTo("PENDING");
        assertThat(submitted.path("flags").toString()).contains("PHONE", "LINK");
        assertThat(submitted.path("approvePath").asString()).isEqualTo("/v1/product-reviews/" + flagged.id() + "/approve");
        assertThat(submitted.path("sellerOrgId").asString()).isEqualTo(RETAIL_ORG);
        assertThat(count("catalog.review.published", flagged.id())).isZero();

        // not public while pending, visible to its author as pending
        assertThat(reviews.publicReviews(p.atin().value(), null, ReviewSort.NEWEST, 0, 10).page().items())
                .extracting(ProductReview::id).containsExactly(ok.id());
        CURRENT.set(customer(bola));
        assertThat(reviews.mine(p.atin().value()).review().status()).isEqualTo(ReviewStatus.PENDING);

        CURRENT.set(customer(cust()));
        assertThatThrownBy(() -> moderation.approve(flagged.id(), null)).isInstanceOf(ForbiddenActionException.class);
        CURRENT.set(ADMIN);
        ProductReview approved = moderation.approve(flagged.id(), "The number is the shop's");
        assertThat(approved.status()).isEqualTo(ReviewStatus.PUBLISHED);
        assertThat(reviews.rating(p.atin().value()).count()).isEqualTo(2);
        assertThat(reviews.rating(p.atin().value()).average()).isEqualByComparingTo("3.00");
        assertThat(count("catalog.review.published", flagged.id())).isEqualTo(1);
        // a retried decision changes nothing and publishes nothing
        assertThat(moderation.approve(flagged.id(), null).version()).isEqualTo(approved.version());
        assertThat(count("catalog.review.published", flagged.id())).isEqualTo(1);
    }

    @Test
    void staffRejectPendingReviewsAndRemovePublishedOnesWithReasonsTheCustomerSees() {
        Product p = liveProduct();
        String atin = p.atin().value();
        String ada = cust();
        ProductReview pending = reviewed(p, ada, "Ada Obi", 1, "This is bullshit, buy from www.rival.shop");
        assertThat(pending.status()).isEqualTo(ReviewStatus.PENDING);
        assertThat(pending.flags()).hasSize(2);

        CURRENT.set(ADMIN);
        assertThatThrownBy(() -> moderation.reject(pending.id(), ReviewReasonCode.OTHER, null))
                .isInstanceOf(CatalogRuleViolation.class);
        ProductReview rejected = moderation.reject(pending.id(), ReviewReasonCode.SPAM, "Advertises a rival shop");
        assertThat(rejected.status()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(count("catalog.review.rejected", pending.id())).isEqualTo(1);
        assertThat(moderation.reject(pending.id(), ReviewReasonCode.SPAM, null).version()).isEqualTo(rejected.version());
        assertThat(count("catalog.review.rejected", pending.id())).isEqualTo(1);

        CURRENT.set(customer(ada));
        MyReview mine = reviews.mine(atin);
        assertThat(mine.review().status()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(mine.review().moderation().reason()).isEqualTo(ReviewReasonCode.SPAM);
        assertThat(mine.review().moderation().note()).isEqualTo("Advertises a rival shop");
        // an edit goes back to moderation, however clean it is
        ProductReview rewritten = reviews.edit(atin, draft(2, "Reworded without links"));
        assertThat(rewritten.status()).isEqualTo(ReviewStatus.PENDING);
        assertThat(count("catalog.review.submitted", pending.id())).isEqualTo(2);

        CURRENT.set(ADMIN);
        assertThat(moderation.approve(pending.id(), null).status()).isEqualTo(ReviewStatus.PUBLISHED);
        assertThat(reviews.rating(atin).count()).isEqualTo(1);
        // a published review cannot be rejected, only removed, and removal updates the rating
        assertThatThrownBy(() -> moderation.reject(pending.id(), ReviewReasonCode.SPAM, null))
                .isInstanceOf(ReviewStateConflict.class);
        ProductReview removed = moderation.remove(pending.id(), ReviewReasonCode.OFF_TOPIC, null);
        assertThat(removed.status()).isEqualTo(ReviewStatus.REMOVED);
        assertThat(reviews.rating(atin).count()).isZero();
        assertThat(payload("catalog.product.rating_changed", p.id().toString()).path("count").asInt()).isZero();
        assertThat(count("catalog.review.removed", pending.id())).isEqualTo(1);
        // reinstating a wrong removal
        assertThat(moderation.approve(pending.id(), "Removed by mistake").status()).isEqualTo(ReviewStatus.PUBLISHED);
        assertThat(reviews.rating(atin).count()).isEqualTo(1);
    }

    @Test
    void staffListReviewsByStateAndProduct() {
        Product p = liveProduct();
        ProductReview a = reviewed(p, cust(), "Ada Obi", 5, "Fine");
        ProductReview b = reviewed(p, cust(), "Bola Ade", 2, "mail b@c.co");
        CURRENT.set(ADMIN);
        PageView<ProductReview> pending = moderation.list(ReviewStatus.PENDING, p.atin().value(), 0, 10);
        assertThat(pending.items()).extracting(ProductReview::id).containsExactly(b.id());
        PageView<ProductReview> all = moderation.list(null, p.atin().value().toLowerCase(Locale.ROOT), 0, 10);
        assertThat(all.items()).extracting(ProductReview::id).containsExactlyInAnyOrder(a.id(), b.id());
        assertThat(moderation.get(a.id()).customerId()).startsWith("cust-");
        assertThatThrownBy(() -> moderation.list(ReviewStatus.DELETED, null, 0, 10))
                .isInstanceOf(CatalogRuleViolation.class);
        CURRENT.set(sellerMember(RETAIL_ORG));
        assertThatThrownBy(() -> moderation.list(null, null, 0, 10)).isInstanceOf(ForbiddenActionException.class);
    }

    // ------------------------------------------------------------------ edit and delete

    @Test
    void editsWorkForThirtyDaysAndDeleteFreesTheSlot() {
        Product p = liveProduct();
        String atin = p.atin().value();
        String ada = cust();
        ProductReview r = reviewed(p, ada, "Ada Obi", 5, "Great");
        CURRENT.set(customer(ada));

        ProductReview edited = reviews.edit(atin, draft(3, "Faded after the first wash"));
        assertThat(edited.rating()).isEqualTo(3);
        assertThat(edited.status()).isEqualTo(ReviewStatus.PUBLISHED);
        assertThat(count("catalog.review.updated", r.id())).isEqualTo(1);
        assertThat(reviews.rating(atin).average()).isEqualByComparingTo("3.00");

        jdbc.sql("update product_review set created_at = now() - interval '31 days' where id = :id")
                .param("id", r.id()).update();
        assertThat(reviews.mine(atin).editable()).isFalse();
        assertThatThrownBy(() -> reviews.edit(atin, draft(5, "Too late")))
                .isInstanceOf(ReviewStateConflict.class)
                .extracting(e -> ((ReviewStateConflict) e).code()).isEqualTo("catalog.review-edit-window-closed");

        reviews.delete(atin);
        assertThat(reviews.mine(atin).review()).isNull();
        assertThat(reviews.mine(atin).eligible()).isTrue();
        assertThat(reviews.rating(atin).count()).isZero();
        assertThat(count("catalog.review.deleted", r.id())).isEqualTo(1);
        assertThat(jdbc.sql("select status from product_review where id = :id").param("id", r.id())
                .query(String.class).single()).isEqualTo("DELETED");
        assertThatThrownBy(() -> reviews.delete(atin)).isInstanceOf(NotFoundException.class);
        // the customer can review the product again
        assertThat(reviews.write(atin, "Ada Obi", draft(4, "Second thoughts")).status())
                .isEqualTo(ReviewStatus.PUBLISHED);
    }

    @Test
    void anEditThatTripsTheChecksTakesAPublishedReviewOffTheRating() {
        Product p = liveProduct();
        String atin = p.atin().value();
        String ada = cust();
        ProductReview r = reviewed(p, ada, "Ada Obi", 5, "Great");
        assertThat(reviews.rating(atin).count()).isEqualTo(1);
        CURRENT.set(customer(ada));
        ProductReview edited = reviews.edit(atin, draft(5, "Great, email me at ada@example.com"));
        assertThat(edited.status()).isEqualTo(ReviewStatus.PENDING);
        assertThat(reviews.rating(atin).count()).isZero();
        assertThat(count("catalog.review.submitted", r.id())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ reports and seller replies

    @Test
    void customersAndSellersReportOnceAndTheSellerRepliesOnceButCannotHide() {
        Product p = liveProduct();
        String author = cust();
        ProductReview r = reviewed(p, author, "Ada Obi", 1, "Rude sellers");

        CURRENT.set(customer(author));
        assertThatThrownBy(() -> reviews.report(r.id(), ReviewReasonCode.SPAM, null))
                .isInstanceOf(ForbiddenActionException.class)
                .extracting(e -> ((ForbiddenActionException) e).code()).isEqualTo("catalog.review-own-report");

        CURRENT.set(customer(cust()));
        ReportResult first = reviews.report(r.id(), ReviewReasonCode.ABUSIVE, "Insults the seller");
        assertThat(first.created()).isTrue();
        assertThat(first.reports()).isEqualTo(1);
        ReportResult again = reviews.report(r.id(), ReviewReasonCode.SPAM, null);
        assertThat(again.created()).isFalse();
        assertThat(again.reports()).isEqualTo(1);
        assertThat(count("catalog.review.reported", r.id())).isEqualTo(1);
        JsonNode reported = payload("catalog.review.reported", r.id().toString());
        assertThat(reported.path("reportReason").asString()).isEqualTo("ABUSIVE");
        assertThat(reported.path("reportCount").asInt()).isEqualTo(1);
        assertThat(reported.path("status").asString()).isEqualTo("PUBLISHED");
        assertThat(reported.path("removePath").asString()).endsWith("/remove");

        CURRENT.set(sellerMember(RETAIL_ORG));
        ReportResult bySeller = reviews.report(r.id(), ReviewReasonCode.CONFLICT_OF_INTEREST, null);
        assertThat(bySeller.created()).isTrue();
        assertThat(bySeller.reports()).isEqualTo(2);
        assertThat(count("catalog.review.reported", r.id())).isEqualTo(2);
        // a seller of another organisation, and staff, cannot report
        CURRENT.set(sellerMember("org-other"));
        assertThatThrownBy(() -> reviews.report(r.id(), ReviewReasonCode.SPAM, null))
                .isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(() -> seller.reply(r.id(), "Hi")).isInstanceOf(NotFoundException.class);
        assertThat(seller.list(null, false, 0, 10).items()).isEmpty();

        CURRENT.set(sellerMember(RETAIL_ORG));
        PageView<ProductReview> mine = seller.list(null, true, 0, 10);
        assertThat(mine.items()).extracting(ProductReview::id).contains(r.id());
        ProductReview replied = seller.reply(r.id(), "  We are sorry to hear that.  ");
        assertThat(replied.reply().text()).isEqualTo("We are sorry to hear that.");
        assertThat(count("catalog.review.replied", r.id())).isEqualTo(1);
        assertThatThrownBy(() -> seller.reply(r.id(), "Again")).isInstanceOf(ReviewStateConflict.class)
                .extracting(e -> ((ReviewStateConflict) e).code()).isEqualTo("catalog.review-already-replied");
        assertThat(seller.list(null, true, 0, 100).items()).extracting(ProductReview::id).doesNotContain(r.id());
        // the reply is public with the review, and the customer sees it
        PublicReviews pub = reviews.publicReviews(p.atin().value(), null, ReviewSort.NEWEST, 0, 10);
        assertThat(pub.page().items().getFirst().reply().text()).isEqualTo("We are sorry to hear that.");

        // sellers have no way to edit or hide: the use cases offer none, and moderation is staff only
        assertThatThrownBy(() -> moderation.remove(r.id(), ReviewReasonCode.SPAM, null))
                .isInstanceOf(ForbiddenActionException.class);
        CURRENT.set(ADMIN);
        assertThat(moderation.get(r.id()).reportCount()).isEqualTo(2);
    }

    @Test
    void anEditClearsTheReportsOfTheOldText() {
        Product p = liveProduct();
        String author = cust();
        ProductReview r = reviewed(p, author, "Ada Obi", 2, "Not great");
        CURRENT.set(customer(cust()));
        reviews.report(r.id(), ReviewReasonCode.SPAM, null);
        CURRENT.set(customer(author));
        reviews.edit(p.atin().value(), draft(2, "Not great, updated"));
        assertThat(jdbc.sql("select count(*) from product_review_report where review_id = :id").param("id", r.id())
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select report_count from product_review where id = :id").param("id", r.id())
                .query(Integer.class).single()).isZero();
        CURRENT.set(customer(cust()));
        assertThat(reviews.report(r.id(), ReviewReasonCode.SPAM, null).created()).isTrue();
    }

    @Test
    void onlyPublishedReviewsCanBeReportedOrAnsweredAndSellersSeeNoCustomerIdentity() {
        Product p = liveProduct();
        ProductReview pending = reviewed(p, cust(), "Ada Obi", 3, "Call 0803 123 4567");
        CURRENT.set(customer(cust()));
        assertThatThrownBy(() -> reviews.report(pending.id(), ReviewReasonCode.SPAM, null))
                .isInstanceOf(NotFoundException.class);
        CURRENT.set(sellerMember(RETAIL_ORG));
        assertThatThrownBy(() -> seller.reply(pending.id(), "Thanks")).isInstanceOf(ReviewStateConflict.class)
                .extracting(e -> ((ReviewStateConflict) e).code()).isEqualTo("catalog.review-not-published");
        assertThat(seller.list(null, false, 0, 100).items()).extracting(ProductReview::id)
                .doesNotContain(pending.id());
    }

    // ------------------------------------------------------------------ public reads

    @Test
    void publicListsAreSortedFilteredAndPaged() {
        Product p = liveProduct();
        String atin = p.atin().value();
        ProductReview one = reviewed(p, cust(), "Ada Obi", 1, "Poor");
        ProductReview five = reviewed(p, cust(), "Bola Ade", 5, "Superb");
        ProductReview four = reviewed(p, cust(), "Chi Eze", 4, "Good");
        ProductReview five2 = reviewed(p, cust(), "Dayo Bello", 5, "Also superb");

        assertThat(reviews.publicReviews(atin, null, ReviewSort.NEWEST, 0, 10).page().items())
                .extracting(ProductReview::id).containsExactly(five2.id(), four.id(), five.id(), one.id());
        assertThat(reviews.publicReviews(atin, null, ReviewSort.HIGHEST, 0, 10).page().items())
                .extracting(ProductReview::id).containsExactly(five2.id(), five.id(), four.id(), one.id());
        assertThat(reviews.publicReviews(atin, null, ReviewSort.LOWEST, 0, 10).page().items())
                .extracting(ProductReview::id).containsExactly(one.id(), four.id(), five2.id(), five.id());
        PublicReviews fives = reviews.publicReviews(atin, 5, ReviewSort.NEWEST, 0, 10);
        assertThat(fives.page().items()).extracting(ProductReview::id).containsExactly(five2.id(), five.id());
        assertThat(fives.page().total()).isEqualTo(2);
        assertThat(fives.rating().count()).as("the rating is for all stars").isEqualTo(4);

        PublicReviews paged = reviews.publicReviews(atin, null, ReviewSort.NEWEST, 1, 3);
        assertThat(paged.page().items()).extracting(ProductReview::id).containsExactly(one.id());
        assertThat(paged.page().total()).isEqualTo(4);
        assertThatThrownBy(() -> reviews.publicReviews(atin, 6, ReviewSort.NEWEST, 0, 10))
                .isInstanceOf(CatalogRuleViolation.class);
        assertThatThrownBy(() -> reviews.publicReviews(atin, null, ReviewSort.NEWEST, 0, 51))
                .isInstanceOf(CatalogRuleViolation.class);
        assertThatThrownBy(() -> reviews.publicReviews("AT00000000", null, ReviewSort.NEWEST, 0, 10))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void publicEndpointsShowVerifiedPurchaseFirstNameAndInitialAndNothingPrivate() throws Exception {
        Product p = liveProduct();
        String atin = p.atin().value();
        String ada = cust();
        reviewed(p, ada, "Adaeze Nwosu", 5, "Tastes like home");
        reviewed(p, cust(), "Bola Ade", 3, "Fine");

        JsonNode page = get("/v1/public/products/" + atin + "/reviews?sort=HIGHEST&size=1");
        assertThat(page.path("total").asInt()).isEqualTo(2);
        assertThat(page.path("items")).hasSize(1);
        JsonNode item = page.path("items").get(0);
        assertThat(item.path("reviewerName").asString()).isEqualTo("Adaeze N.");
        assertThat(item.path("verifiedPurchase").asBoolean()).isTrue();
        assertThat(item.path("rating").asInt()).isEqualTo(5);
        assertThat(item.path("photos").get(0).path("url").asString()).isEqualTo(PHOTO.url());
        assertThat(page.path("rating").path("count").asInt()).isEqualTo(2);
        assertThat(page.path("rating").path("average").asDouble()).isEqualTo(4.0);
        assertThat(page.toString()).doesNotContain(ada).doesNotContain("order").doesNotContain("customerId");

        JsonNode rating = get("/v1/public/products/" + atin + "/rating");
        assertThat(rating.path("breakdown")).hasSize(5);
        assertThat(rating.path("breakdown").get(4).asInt()).isEqualTo(1);

        JsonNode product = get("/v1/public/products/" + atin);
        assertThat(product.path("rating").path("count").asInt()).isEqualTo(2);
        assertThat(product.path("rating").path("average").asDouble()).isEqualTo(4.0);
        JsonNode variant = get("/v1/public/variants/" + p.variants().getFirst().atin().value());
        assertThat(variant.path("product").path("rating").path("count").asInt()).isEqualTo(2);

        HttpResponse<String> missing = send("/v1/public/products/AT00000000/reviews");
        assertThat(missing.statusCode()).isEqualTo(404);
        HttpResponse<String> badSort = send("/v1/public/products/" + atin + "/reviews?sort=BEST");
        assertThat(badSort.statusCode()).isEqualTo(400);
    }

    @Test
    void aProductWithoutReviewsHasAZeroRatingOnItsPage() throws Exception {
        Product p = liveProduct();
        JsonNode product = get("/v1/public/products/" + p.atin().value());
        assertThat(product.path("rating").path("count").asInt()).isZero();
        assertThat(product.path("rating").path("average").asDouble()).isZero();
        assertThat(product.path("rating").path("breakdown")).hasSize(5);
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = send(path);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    private HttpResponse<String> send(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
