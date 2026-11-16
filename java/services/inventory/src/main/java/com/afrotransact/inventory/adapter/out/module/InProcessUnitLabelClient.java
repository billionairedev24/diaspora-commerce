package com.afrotransact.inventory.adapter.out.module;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.afrotransact.catalog.api.LabelApi;
import com.afrotransact.inventory.port.UnitLabelClient;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.InProcessLink;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link UnitLabelClient} through catalog's published {@link LabelApi} inside this JVM (mode {@code in-process}): the same
 * labels as {@code GET /v1/labels/units/{atin}} and {@code POST /v1/labels/batch}, entered as inventory's caller principal
 * (scope {@code catalog:read}). The AT-FN code is read as the HTTP client reads it: from the unit code when catalog gives one,
 * otherwise from the label's {@code <title>}. Failures are {@link LabelServiceException}s with the HTTP client's retry rule: a
 * 5xx, 401, 403 or 429 is retryable, another 4xx is a rejection.
 */
@Component
@ConditionalOnModuleMode(caller = "inventory", callee = "catalog", value = ModuleMode.IN_PROCESS)
class InProcessUnitLabelClient implements UnitLabelClient {

    private static final Pattern AT_FN = Pattern.compile("(?<![0-9A-Z])ATF[0-9A-Z]{7}(?![0-9A-Z])");
    private static final Pattern TITLE = Pattern.compile("<title>([^<]*)</title>");

    private final InProcessLink<LabelApi> catalog;

    InProcessUnitLabelClient(ObjectProvider<LabelApi> api, ObjectProvider<CallerPrincipals> callers,
                             ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(LabelApi.class, true, false).length > 0, "inventory",
                "catalog", "at.inventory.catalog.mode");
        this.catalog = new InProcessLink<>("inventory", "catalog", api::getObject, callers::getObject);
    }

    @Override
    public Optional<String> unitLabelCode(String atin, String ownerOrg) {
        LabelApi.LabelOutput out = call(api -> api.unitLabel(atin, ownerOrg));
        String header = out.unitCode();
        if (header != null && AT_FN.matcher(header.trim().toUpperCase(Locale.ROOT)).matches()) {
            return Optional.of(header.trim().toUpperCase(Locale.ROOT));
        }
        String svg = out.content() == null ? "" : new String(out.content(), StandardCharsets.UTF_8);
        Matcher title = TITLE.matcher(svg);
        if (title.find()) {
            Matcher code = AT_FN.matcher(title.group(1));
            return code.find() ? Optional.of(code.group()) : Optional.empty();
        }
        return Optional.empty();
    }

    @Override
    public RenderedLabels render(List<LabelSpec> labels, String format) {
        String f = format == null ? "PDF" : format.toUpperCase(Locale.ROOT);
        LabelApi.LabelOutput out = call(api -> api.renderBatch(labels.stream().map(l -> new LabelApi.Label(l.kind(),
                l.data(), l.caption(), l.title(), l.lines(), l.copies(), l.expiry())).toList(), f));
        return new RenderedLabels(out.contentType() == null ? ("ZPL".equals(f) ? "text/plain" : "application/pdf")
                : out.contentType(), "labels." + f.toLowerCase(Locale.ROOT),
                out.content() == null ? new byte[0] : out.content());
    }

    private <T> T call(Function<LabelApi, T> call) {
        try {
            return catalog.call(call);
        } catch (ModuleApiException e) {
            int status = e.status();
            boolean retryable = status >= 500 || status == 401 || status == 403 || status == 429;
            throw new LabelServiceException("catalog answered " + status + ": " + e.getMessage(), retryable, e);
        } catch (RuntimeException e) {
            throw new LabelServiceException("catalog failed the in-process call: " + e.getMessage(), true, e);
        }
    }
}
