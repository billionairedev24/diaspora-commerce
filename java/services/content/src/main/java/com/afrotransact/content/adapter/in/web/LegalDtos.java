package com.afrotransact.content.adapter.in.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.afrotransact.content.application.LegalService.LegalPage;
import com.afrotransact.content.application.LegalService.VersionRef;
import com.afrotransact.content.application.LegalSummary;
import com.afrotransact.content.domain.legal.Heading;
import com.afrotransact.content.domain.legal.LegalVersion;
import com.afrotransact.content.domain.legal.LegalVersionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/** Legal center bodies. */
final class LegalDtos {

    private LegalDtos() {
    }

    @Schema(name = "LegalDocumentSummary")
    record SummaryDto(String key, String title, String version, LocalDate effectiveDate, String href,
                      boolean draftBanner) {

        static SummaryDto of(LegalSummary s) {
            return new SummaryDto(s.key(), s.title(), s.version(), s.effectiveDate(), s.href(), s.draftBanner());
        }
    }

    @Schema(name = "LegalDocumentIndex")
    record IndexResponse(List<SummaryDto> documents) {
    }

    @Schema(name = "LegalHeading")
    record HeadingDto(String id, String title) {

        static HeadingDto of(Heading h) {
            return new HeadingDto(h.id(), h.title());
        }
    }

    @Schema(name = "LegalVersionRef")
    record VersionDto(String version, LocalDate effectiveDate, LegalVersionStatus status, Instant archivedAt,
                      String changeSummary) {

        static VersionDto of(VersionRef r) {
            return new VersionDto(r.version(), r.effectiveDate(), r.status(), r.archivedAt(), r.changeSummary());
        }
    }

    @Schema(name = "LegalPage", description = "A rendered legal document. English is binding; other locales fall back "
            + "to English with translationFallback = true")
    record PageResponse(String key, String title, String version, LocalDate effectiveDate, LegalVersionStatus status,
                        boolean draftBanner, String locale, boolean binding, boolean translationFallback,
                        String bindingLocale, @Schema(description = "Markdown") String body, List<HeadingDto> outline,
                        List<VersionDto> versions, String href, Instant archivedAt) {

        static PageResponse of(LegalPage p) {
            LegalVersion v = p.version();
            return new PageResponse(v.type().key(), v.type().title(), v.version().toString(), v.effectiveDate(),
                    v.status(), v.draftBanner(), p.body().locale(), p.body().binding(), p.body().fallback(),
                    LegalVersion.BINDING_LOCALE, p.body().markdown(), p.outline().stream().map(HeadingDto::of).toList(),
                    p.history().stream().map(VersionDto::of).toList(), p.href(), v.archivedAt());
        }
    }

    @Schema(name = "LegalVersion")
    record VersionResponse(String key, String version, LocalDate effectiveDate, LegalVersionStatus status,
                           boolean draftBanner, List<String> locales, String changeSummary, String createdBy,
                           Instant createdAt) {

        static VersionResponse of(LegalVersion v) {
            return new VersionResponse(v.type().key(), v.version().toString(), v.effectiveDate(), v.status(),
                    v.draftBanner(), List.copyOf(v.bodies().keySet()), v.changeSummary(), v.createdBy(), v.createdAt());
        }
    }

    @Schema(name = "RaiseLegalVersionRequest")
    record RaiseRequest(@NotBlank @Schema(example = "3.3") String version, @NotNull LocalDate effectiveDate,
                        @Schema(description = "Show the counsel-review banner (default true)") Boolean draftBanner,
                        @NotEmpty @Schema(description = "Markdown per locale; en is required") Map<String, String> bodies,
                        String changeSummary) {
    }

    @Schema(name = "AmendLegalVersionRequest", description = "Counsel sign-off (draftBanner false), effective date or translations")
    record AmendRequest(Boolean draftBanner, LocalDate effectiveDate, Map<String, String> bodies) {
    }
}
