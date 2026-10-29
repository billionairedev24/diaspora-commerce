package com.afrotransact.content.adapter.in.web;

import java.time.Instant;
import java.util.List;

import com.afrotransact.content.adapter.in.web.HelpDtos.SectionDto;
import com.afrotransact.content.domain.help.HelpArticleStatus;
import com.afrotransact.content.domain.help.HelpDraft;
import com.afrotransact.content.domain.help.HelpRevision;
import com.afrotransact.content.domain.help.HelpTopic;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Staff authoring of help pages: the working draft and the revision history. */
final class HelpAuthoringDtos {

    private HelpAuthoringDtos() {
    }

    @Schema(name = "HelpDraft", description = "The working copy of a help page; saving it changes nothing public")
    record DraftResponse(String slug, HelpTopic topic, String topicTitle, String title, String summary,
                         List<SectionDto> sections, int sortOrder, String changeSummary, long baseVersion,
                         long rowVersion, String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {

        static DraftResponse of(HelpDraft d) {
            return new DraftResponse(d.slug(), d.topic(), d.topic().title(), d.title(), d.summary(),
                    d.sections().stream().map(SectionDto::of).toList(), d.sortOrder(), d.changeSummary(),
                    d.baseVersion(), d.rowVersion(), d.createdBy(), d.createdAt(), d.updatedBy(), d.updatedAt());
        }
    }

    @Schema(name = "SaveHelpDraftRequest", description = "The whole draft as the editor shows it; rowVersion is the "
            + "version the editor loaded (omit on the first save)")
    record DraftRequest(@NotNull HelpTopic topic, @NotBlank String title, @NotBlank String summary,
                        @NotNull List<SectionDto> sections, Integer sortOrder, String changeSummary, Long rowVersion) {

        HelpDraft.Edit toEdit() {
            return new HelpDraft.Edit(topic, title, summary, sections.stream().map(SectionDto::toDomain).toList(),
                    sortOrder == null ? 0 : sortOrder, changeSummary);
        }
    }

    @Schema(name = "PublishHelpDraftRequest")
    record PublishRequest(@NotBlank @Schema(description = "Why this is being published (kept in the audit trail)") String reason,
                          @NotNull @Schema(description = "The draft version being published") Long rowVersion) {
    }

    @Schema(name = "RestoreHelpRevisionRequest")
    record RestoreRequest(@NotBlank @Schema(description = "Why this revision is being restored") String reason) {
    }

    @Schema(name = "HelpRevision", description = "One published state of a help page")
    record RevisionResponse(String slug, int revision, HelpTopic topic, String topicTitle, String title, String summary,
                            List<SectionDto> sections, HelpArticleStatus status, int sortOrder, String changeSummary,
                            String reason, String publishedBy, Instant publishedAt) {

        static RevisionResponse of(HelpRevision r) {
            return new RevisionResponse(r.slug(), r.revision(), r.topic(), r.topic().title(), r.title(), r.summary(),
                    r.sections().stream().map(SectionDto::of).toList(), r.status(), r.sortOrder(), r.changeSummary(),
                    r.reason(), r.publishedBy(), r.publishedAt());
        }
    }
}
