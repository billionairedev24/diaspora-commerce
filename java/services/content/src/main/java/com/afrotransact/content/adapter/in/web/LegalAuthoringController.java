package com.afrotransact.content.adapter.in.web;

import java.util.List;

import com.afrotransact.content.adapter.in.web.LegalAuthoringDtos.DraftRequest;
import com.afrotransact.content.adapter.in.web.LegalAuthoringDtos.DraftResponse;
import com.afrotransact.content.adapter.in.web.LegalAuthoringDtos.EntryResponse;
import com.afrotransact.content.adapter.in.web.LegalAuthoringDtos.PublishRequest;
import com.afrotransact.content.adapter.in.web.LegalAuthoringDtos.RestoreRequest;
import com.afrotransact.content.adapter.in.web.LegalAuthoringDtos.SourceResponse;
import com.afrotransact.content.adapter.in.web.LegalDtos.VersionResponse;
import com.afrotransact.content.application.LegalAuthoringService;
import com.afrotransact.content.domain.legal.LegalDocumentType;
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

@Tag(name = "Legal", description = "Legal & privacy center: current documents, versions, counsel sign-off")
@RestController
@RequestMapping("/v1/legal/{type}")
class LegalAuthoringController {

    private final LegalAuthoringService authoring;

    LegalAuthoringController(LegalAuthoringService authoring) {
        this.authoring = authoring;
    }

    @Operation(operationId = "getLegalDraft", summary = "The working draft of a document (content:legal.view)")
    @GetMapping("/draft")
    DraftResponse draft(@PathVariable String type) {
        LegalDocumentType t = typeOf(type);
        return authoring.draft(t).map(DraftResponse::of).orElseThrow(() -> new NotFoundException(
                "content.legal-draft-not-found", "There is no draft of " + t.title()));
    }

    @Operation(operationId = "saveLegalDraft", summary = "Create or change the draft; nothing public changes (content:legal.edit)")
    @PutMapping("/draft")
    DraftResponse save(@PathVariable String type, @Valid @RequestBody DraftRequest body) {
        return DraftResponse.of(authoring.save(typeOf(type), body.toEdit(), body.rowVersion()));
    }

    @Operation(operationId = "discardLegalDraft", summary = "Throw the draft away (content:legal.edit)")
    @DeleteMapping("/draft")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void discard(@PathVariable String type) {
        authoring.discard(typeOf(type));
    }

    @Operation(operationId = "publishLegalDraft", summary = "Publish the draft as a new version; the previous one is archived. "
            + "The person who saved the draft last cannot publish it (content:legal.edit)")
    @PostMapping("/draft/publication")
    @ResponseStatus(HttpStatus.CREATED)
    VersionResponse publish(@PathVariable String type, @Valid @RequestBody PublishRequest body) {
        return VersionResponse.of(authoring.publish(typeOf(type), body.reason(), body.rowVersion()));
    }

    @Operation(operationId = "restoreLegalVersion", summary = "Start a draft from an older version's text (rollback; content:legal.edit)")
    @PostMapping("/draft/restoration")
    @ResponseStatus(HttpStatus.CREATED)
    DraftResponse restore(@PathVariable String type, @Valid @RequestBody RestoreRequest body) {
        return DraftResponse.of(authoring.restore(typeOf(type), body.version()));
    }

    @Operation(operationId = "getLegalHistory", summary = "Every published version with who published it (content:legal.view)")
    @GetMapping("/history")
    List<EntryResponse> history(@PathVariable String type) {
        return authoring.history(typeOf(type)).stream().map(EntryResponse::of).toList();
    }

    @Operation(operationId = "getLegalVersionSource", summary = "One version's Markdown as written (content:legal.view)")
    @GetMapping("/versions/{version}/source")
    SourceResponse source(@PathVariable String type, @PathVariable String version) {
        return SourceResponse.of(authoring.source(typeOf(type), version));
    }

    private static LegalDocumentType typeOf(String key) {
        return LegalDocumentType.find(key).orElseThrow(() -> new NotFoundException("content.legal-not-found",
                "There is no legal document " + key));
    }
}
