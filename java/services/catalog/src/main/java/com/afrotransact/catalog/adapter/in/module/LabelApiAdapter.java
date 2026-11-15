package com.afrotransact.catalog.adapter.in.module;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.afrotransact.catalog.api.LabelApi;
import com.afrotransact.catalog.application.LabelService;
import com.afrotransact.catalog.application.RenderedLabel;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.barcode.LabelDraft;
import com.afrotransact.catalog.domain.barcode.LabelFormat;
import com.afrotransact.catalog.domain.barcode.LabelKind;
import com.afrotransact.catalog.domain.barcode.SheetLayout;
import com.afrotransact.catalog.domain.barcode.Symbology;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of the published {@link LabelApi}: the use cases behind {@code GET /v1/labels/units/{atin}} and
 * {@code POST /v1/labels/batch}, entered under the calling module's principal. {@link LabelService} requires {@code catalog:read}
 * or {@code catalog:write} exactly as it does for a token; label data it rejects leaves as status 422, as over HTTP.
 */
@Component
class LabelApiAdapter implements LabelApi {

    private final LabelService labels;
    private final ModuleEntry entry;

    LabelApiAdapter(LabelService labels, ActorResolver actors) {
        this.labels = labels;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public LabelOutput unitLabel(String atin, String ownerOrgId) {
        return entry.run(() -> output(labels.unitLabel(atin, ownerOrgId, LabelFormat.SVG, 1)),
                LabelApiAdapter::failure);
    }

    @Override
    public LabelOutput renderBatch(List<Label> specs, String format) {
        return entry.run(() -> {
            LabelFormat f = LabelFormat.valueOf((format == null ? "PDF" : format).strip().toUpperCase(Locale.ROOT));
            List<LabelDraft> drafts = specs.stream().map(l -> new LabelDraft(LabelKind.valueOf(l.kind()),
                    Symbology.CODE_128, l.data(), null, null, null, null, null, l.expiry(), null, null, l.caption(),
                    l.title(), l.lines(), l.copies())).toList();
            return output(labels.renderBatch(drafts, f, new SheetLayout(SheetLayout.Mode.LABEL, null)));
        }, LabelApiAdapter::failure);
    }

    private static LabelOutput output(RenderedLabel r) {
        return new LabelOutput(r.contentType(), r.fileName(), r.content(), r.unitCode());
    }

    private static Optional<ModuleApiException> failure(RuntimeException ex) {
        if (ex instanceof CatalogRuleViolation rule) {
            return Optional.of(new ModuleApiException(422, "catalog.rule-violation", rule.getMessage(),
                    rule.fieldErrors().entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList(), ex));
        }
        return Optional.empty();
    }
}
