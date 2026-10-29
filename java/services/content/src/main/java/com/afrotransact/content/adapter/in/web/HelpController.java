package com.afrotransact.content.adapter.in.web;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.afrotransact.content.adapter.in.web.HelpDtos.AdminArticleDto;
import com.afrotransact.content.adapter.in.web.HelpDtos.ArticleDto;
import com.afrotransact.content.adapter.in.web.HelpDtos.ArticleRequest;
import com.afrotransact.content.adapter.in.web.HelpDtos.PageDto;
import com.afrotransact.content.application.HelpAuthoringService;
import com.afrotransact.content.application.HelpCenterService;
import com.afrotransact.content.domain.help.HelpTopic;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Help center", description = "Help pages (shipping, returns, press, advertising…) edited without deployments")
@RestController
@RequestMapping("/v1/help")
class HelpController {

    private static final CacheControl SHORT = CacheControl.maxAge(Duration.ofMinutes(1)).cachePublic();

    private final HelpCenterService help;
    private final HelpAuthoringService authoring;

    HelpController(HelpCenterService help, HelpAuthoringService authoring) {
        this.help = help;
        this.authoring = authoring;
    }

    @Operation(operationId = "listHelpArticles", summary = "Published help articles (the hub excluded), by topic and text, a page at a time")
    @SecurityRequirements
    @GetMapping("/articles")
    ResponseEntity<PageDto> articles(@RequestParam(required = false) HelpTopic topic,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(required = false) String cursor,
                                     @RequestParam(required = false, defaultValue = "20") int limit) {
        return ResponseEntity.ok().cacheControl(SHORT).body(PageDto.of(help.published(topic, q, cursor, limit)));
    }

    @Operation(operationId = "getHelpArticle", summary = "A published help article (help-center is the hub)")
    @SecurityRequirements
    @GetMapping("/articles/{slug}")
    ResponseEntity<ArticleDto> article(@PathVariable String slug) {
        return ResponseEntity.ok().cacheControl(SHORT).body(ArticleDto.of(help.publishedArticle(slug)));
    }

    @Operation(operationId = "allHelpArticles", summary = "Every help article including drafts (content:site.view)")
    @GetMapping("/admin/articles")
    List<AdminArticleDto> all() {
        Set<String> withDraft = authoring.slugsWithDraft();
        Map<String, Integer> latest = authoring.latestRevisions();
        return help.all().stream().map(a -> AdminArticleDto.of(a, withDraft.contains(a.slug()),
                latest.getOrDefault(a.slug(), 1))).toList();
    }

    @Operation(operationId = "saveHelpArticle", summary = "Create or change a help article (content:site.edit); expectedVersion guards concurrent edits")
    @PutMapping("/articles/{slug}")
    ArticleDto save(@PathVariable String slug, @Valid @RequestBody ArticleRequest body) {
        return ArticleDto.of(help.save(body.toDomain(slug), body.expectedVersion()));
    }
}
