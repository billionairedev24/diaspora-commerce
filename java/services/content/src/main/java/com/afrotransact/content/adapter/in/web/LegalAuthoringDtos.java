package com.afrotransact.content.adapter.in.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.afrotransact.content.application.LegalAuthoringService;
import com.afrotransact.content.domain.legal.LegalDraft;
import com.afrotransact.content.domain.legal.LegalVersion;
import com.afrotransact.content.domain.legal.LegalVersionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/** Staff authoring of legal documents: the working draft, the history with authors and the text as written. */
final class LegalAuthoringDtos {

    private LegalAuthoringDtos() {
    }

    @Schema(name = "LegalDraft", description = "The working copy of a document; saving it changes nothing public")
    record DraftResponse(String key, String title, String version, String baseVersion, LocalDate effectiveDate, boolean draftBanner,
                         Map<String, String> bodies, List<String> needsReview, String changeSummary,
                         Map<String, Integer> unresolvedMarkers, long rowVersion, String createdBy, Instant createdAt,
                         String updatedBy, Instant updatedAt) {

        static DraftResponse of(LegalDraft d) {
            return new DraftResponse(d.type().key(), d.type().title(), d.version().toString(), d.baseVersion().toString(),
                    d.effectiveDate(), d.draftBanner(), d.bodies(), List.copyOf(d.needsReview()), d.changeSummary(),
                    d.unresolvedMarkers(), d.rowVersion(), d.createdBy(), d.createdAt(), d.updatedBy(), d.updatedAt());
        }
    }

    @Schema(name = "SaveLegalDraftRequest", description = "The whole draft as the editor shows it; rowVersion is the "
            + "version the editor loaded (omit on the first save)")
    record DraftRequest(@NotBlank @Schema(example = "3.3") String version, @NotNull LocalDate effectiveDate,
                        @Schema(description = "Show the counsel-review banner (default true)") Boolean draftBanner,
                        @NotEmpty @Schema(description = "Markdown per locale; en is required") Map<String, String> bodies,
                        @Schema(description = "Translations a reviewer still has to check") Set<String> needsReview,
                        String changeSummary, Long rowVersion) {

        LegalDraft.Edit toEdit() {
            return new LegalDraft.Edit(version, effectiveDate, draftBanner == null || draftBanner, bodies,
                    needsReview == null ? Set.of() : needsReview, changeSummary);
        }
    }

    @Schema(name = "PublishLegalDraftRequest")
    record PublishRequest(@NotBlank @Schema(description = "Why this is being published (kept in the audit trail)") String reason,
                          @NotNull @Schema(description = "The draft version the approver reviewed") Long rowVersion) {
    }

    @Schema(name = "RestoreLegalVersionRequest")
    record RestoreRequest(@NotBlank @Schema(example = "3.1") String version) {
    }

    @Schema(name = "LegalVersionEntry", description = "A published version with who published it and when (staff)")
    record EntryResponse(String version, LocalDate effectiveDate, LegalVersionStatus status, boolean draftBanner,
                         List<String> locales, String changeSummary, String publishedBy, Instant publishedAt,
                         Instant archivedAt) {

        static EntryResponse of(LegalVersion v) {
            return new EntryResponse(v.version().toString(), v.effectiveDate(), v.status(), v.draftBanner(),
                    List.copyOf(v.bodies().keySet()), v.changeSummary(), v.createdBy(), v.createdAt(), v.archivedAt());
        }
    }

    @Schema(name = "LegalOverviewEntry", description = "A document's current version, who published it, and its draft if any (staff)")
    record OverviewEntry(String key, String title, String version, LocalDate effectiveDate, boolean draftBanner,
                         String publishedBy, Instant publishedAt, boolean hasDraft, String draftVersion,
                         String draftUpdatedBy, Instant draftUpdatedAt) {

        static OverviewEntry of(LegalAuthoringService.Overview o) {
            LegalVersion v = o.current();
            return new OverviewEntry(v.type().key(), v.type().title(), v.version().toString(), v.effectiveDate(),
                    v.draftBanner(), v.createdBy(), v.createdAt(), o.draft().isPresent(),
                    o.draft().map(d -> d.version().toString()).orElse(null),
                    o.draft().map(LegalDraft::updatedBy).orElse(null), o.draft().map(LegalDraft::updatedAt).orElse(null));
        }
    }

    @Schema(name = "LegalVersionSource", description = "One published version with its Markdown as written")
    record SourceResponse(String key, String title, String version, LocalDate effectiveDate, LegalVersionStatus status,
                          boolean draftBanner, Map<String, String> bodies, String changeSummary, String publishedBy,
                          Instant publishedAt, Instant archivedAt) {

        static SourceResponse of(LegalVersion v) {
            return new SourceResponse(v.type().key(), v.type().title(), v.version().toString(), v.effectiveDate(), v.status(),
                    v.draftBanner(), v.bodies(), v.changeSummary(), v.createdBy(), v.createdAt(), v.archivedAt());
        }
    }
}
