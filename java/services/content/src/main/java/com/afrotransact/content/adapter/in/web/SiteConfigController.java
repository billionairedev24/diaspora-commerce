package com.afrotransact.content.adapter.in.web;

import java.util.List;

import com.afrotransact.content.adapter.in.web.SiteDtos.PublicSiteConfig;
import com.afrotransact.content.adapter.in.web.SiteDtos.RevisionDto;
import com.afrotransact.content.adapter.in.web.SiteDtos.SiteConfigSettings;
import com.afrotransact.content.adapter.in.web.SiteDtos.UpdateSiteConfigRequest;
import com.afrotransact.content.application.ContentSettings;
import com.afrotransact.content.application.SiteConfigService;
import com.afrotransact.content.application.SiteConfigService.PublishedSiteConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Site config", description = "Social links, contacts, app links, locales and legal versions — no deployments")
@RestController
@RequestMapping("/v1/site-config")
class SiteConfigController {

    private static final String REVISION = "X-Config-Revision";

    private final SiteConfigService site;
    private final ContentSettings settings;

    SiteConfigController(SiteConfigService site, ContentSettings settings) {
        this.site = site;
        this.settings = settings;
    }

    @Operation(summary = "Public site configuration (ETag + revision; 304 on If-None-Match)")
    @SecurityRequirements
    @GetMapping
    ResponseEntity<PublicSiteConfig> get() {
        PublishedSiteConfig p = site.published();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(settings.siteMaxAge()).cachePublic().staleWhileRevalidate(
                        settings.siteMaxAge().multipliedBy(10)))
                .eTag(p.etag())
                .header(REVISION, String.valueOf(p.config().revision()))
                .body(PublicSiteConfig.of(p));
    }

    @Operation(summary = "Full editable configuration (content:site.view)")
    @GetMapping("/settings")
    SiteConfigSettings settings() {
        return SiteConfigSettings.of(site.current());
    }

    @Operation(summary = "Save a new revision (content:site.edit). Invalid profile links block saving; "
            + "a stale expectedRevision returns 409")
    @PutMapping
    ResponseEntity<SiteConfigSettings> update(@Valid @RequestBody UpdateSiteConfigRequest body) {
        SiteConfigSettings saved = SiteConfigSettings.of(site.update(body.toDomain(), body.expectedRevision(),
                body.summary()));
        return ResponseEntity.ok().header(REVISION, String.valueOf(saved.revision())).body(saved);
    }

    @Operation(summary = "Revision history (content:site.view)")
    @GetMapping("/revisions")
    List<RevisionDto> revisions() {
        return site.revisions().stream().map(RevisionDto::of).toList();
    }
}
