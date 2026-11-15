package com.afrotransact.seller.adapter.in.web;

import java.time.Instant;
import java.util.List;

import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.seller.application.BusinessPageService;
import com.afrotransact.seller.application.PublicBusinessPage;
import com.afrotransact.seller.application.StorePageService;
import com.afrotransact.seller.domain.StorePage;
import com.afrotransact.seller.port.OrganisationRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag(name = "Storefronts", description = "A seller's or provider's published storefront. Public, read-only.")
@RestController
class PublicStorefrontController {

    private final BusinessPageService pages;
    private final StorePageService store;
    private final OrganisationRepository orgs;
    private final JsonMapper json;

    PublicStorefrontController(BusinessPageService pages, StorePageService store, OrganisationRepository orgs,
                               JsonMapper json) {
        this.pages = pages;
        this.store = store;
        this.orgs = orgs;
        this.json = json;
    }

    @Schema(name = "PublicStorefront")
    record PublicStorefrontResponse(@Schema(description = "SELLER or PROVIDER") String kind, String orgId, String slug, String name, String description, String logoUrl,
                                    String brandColor, String contactEmail,
                                    @Schema(description = "The published settings document") JsonNode config,
                                    Instant publishedAt, Instant orgCreatedAt) {
    }

    @Operation(summary = "A seller's or provider's published storefront settings (no login)",
            description = "404 when there is no page, it is not published, or the organisation is not approved.")
    @GetMapping("/v1/public/storefronts/{slug}")
    PublicStorefrontResponse storefront(@PathVariable String slug) {
        PublicBusinessPage page = pages.find(slug).orElseThrow(this::missing);
        StorePage published = store.publishedFor(page.orgId()).orElseThrow(this::missing);
        Instant since = orgs.findById(page.orgId()).map(o -> o.createdAt()).orElse(null);
        return new PublicStorefrontResponse(page.kind(), page.orgId(), page.slug(), page.name(), page.description(), page.logoUrl(),
                page.brandColor(), page.contactEmail(), json.readTree(published.published()), published.publishedAt(),
                since);
    }

    @Schema(name = "PublishedStorefront")
    record PublishedStorefrontResponse(@Schema(description = "SELLER or PROVIDER") String kind, String orgId, String slug,
                                       Instant publishedAt) {
    }

    @Operation(summary = "Published storefronts of approved sellers and providers (no login)",
            description = "Up to 5000, by address. Used to build the sitemap.")
    @GetMapping("/v1/public/storefronts")
    List<PublishedStorefrontResponse> published() {
        return store.publishedStores(5000).stream()
                .map(p -> new PublishedStorefrontResponse(p.kind(), p.orgId(), p.slug(), p.publishedAt())).toList();
    }

    private NotFoundException missing() {
        return new NotFoundException("seller.storefront-not-found", "No storefront at this address.");
    }
}
