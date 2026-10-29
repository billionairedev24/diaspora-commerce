package com.afrotransact.content.adapter.in.web;

import java.util.List;

import com.afrotransact.content.adapter.in.web.HelpAuthoringDtos.DraftRequest;
import com.afrotransact.content.adapter.in.web.HelpAuthoringDtos.DraftResponse;
import com.afrotransact.content.adapter.in.web.HelpAuthoringDtos.PublishRequest;
import com.afrotransact.content.adapter.in.web.HelpAuthoringDtos.RestoreRequest;
import com.afrotransact.content.adapter.in.web.HelpAuthoringDtos.RevisionResponse;
import com.afrotransact.content.adapter.in.web.HelpDtos.AdminArticleDto;
import com.afrotransact.content.application.HelpAuthoringService;
import com.afrotransact.content.domain.help.HelpArticle;
import com.afrotransact.platform.web.error.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Help center", description = "Help pages (shipping, returns, press, advertising…) edited without deployments")
@RestController
@RequestMapping("/v1/help/articles/{slug}")
class HelpAuthoringController {

    private final HelpAuthoringService authoring;

    HelpAuthoringController(HelpAuthoringService authoring) {
        this.authoring = authoring;
    }

    private AdminArticleDto adminOf(HelpArticle article) {
        return AdminArticleDto.of(article, false, authoring.latestRevisions().getOrDefault(article.slug(), 1));
    }

    @Operation(operationId = "getHelpDraft", summary = "The working draft of a page (content:site.view)")
    @GetMapping("/draft")
    DraftResponse draft(@PathVariable String slug) {
        return authoring.draft(slug).map(DraftResponse::of).orElseThrow(() -> new NotFoundException(
                "content.help-draft-not-found", "There is no draft of " + slug));
    }

    @Operation(operationId = "saveHelpDraft", summary = "Create or change the draft; nothing public changes (content:site.edit)")
    @PutMapping("/draft")
    DraftResponse save(@PathVariable String slug, @Valid @RequestBody DraftRequest body) {
        return DraftResponse.of(authoring.save(slug, body.toEdit(), body.rowVersion()));
    }

    @Operation(operationId = "discardHelpDraft", summary = "Throw the draft away (content:site.edit)")
    @DeleteMapping("/draft")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void discard(@PathVariable String slug) {
        authoring.discard(slug);
    }

    @Operation(operationId = "publishHelpDraft", summary = "Replace the live text with the draft and record a revision (content:site.edit)")
    @PostMapping("/draft/publication")
    @ResponseStatus(HttpStatus.CREATED)
    AdminArticleDto publish(@PathVariable String slug, @Valid @RequestBody PublishRequest body) {
        return adminOf(authoring.publish(slug, body.reason(), body.rowVersion()));
    }

    @Operation(operationId = "listHelpRevisions", summary = "Every published revision of a page, newest first (content:site.view)")
    @GetMapping("/revisions")
    List<RevisionResponse> revisions(@PathVariable String slug) {
        return authoring.revisions(slug).stream().map(RevisionResponse::of).toList();
    }

    @Operation(operationId = "getHelpRevision", summary = "One revision (content:site.view)")
    @GetMapping("/revisions/{revision}")
    RevisionResponse revision(@PathVariable String slug, @PathVariable int revision) {
        return RevisionResponse.of(authoring.revision(slug, revision));
    }

    @Operation(operationId = "restoreHelpRevision", summary = "Publish an older revision's text as the newest revision (content:site.edit)")
    @PostMapping("/revisions/{revision}/restoration")
    @ResponseStatus(HttpStatus.CREATED)
    AdminArticleDto restore(@PathVariable String slug, @PathVariable int revision, @Valid @RequestBody RestoreRequest body) {
        return adminOf(authoring.restore(slug, revision, body.reason()));
    }
}
