package com.afrotransact.seller.adapter.in.module;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import com.afrotransact.seller.api.OrgMirrorApi;
import com.afrotransact.seller.application.OrgEventPayload;
import com.afrotransact.seller.application.OrgMirrorService;
import com.afrotransact.seller.port.Page;
import com.afrotransact.seller.domain.OrgStatus;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link OrgMirrorApi}: the use cases behind the two organisation mirror endpoints under the calling
 * module's principal; {@link OrgMirrorService} checks {@code sellers:directory} as for a token.
 */
@Component
class OrgMirrorApiAdapter implements OrgMirrorApi {

    private final OrgMirrorService mirror;
    private final ModuleEntry entry;

    OrgMirrorApiAdapter(OrgMirrorService mirror, ActorResolver actors) {
        this.mirror = mirror;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public OrgMirrorPage page(int page, int size, List<String> statuses) {
        return entry.run(() -> {
            Set<OrgStatus> wanted = EnumSet.noneOf(OrgStatus.class);
            for (String s : statuses) {
                if (!s.isBlank()) {
                    wanted.add(OrgStatus.valueOf(s.trim().toUpperCase(Locale.ROOT)));
                }
            }
            Page<OrgEventPayload> p = mirror.page(page, size, wanted);
            return new OrgMirrorPage(p.items().stream().map(OrgMirrorApiAdapter::view).toList(), p.total());
        });
    }

    @Override
    public Optional<OrgMirror> one(String orgId) {
        return entry.run(() -> mirror.one(orgId).map(OrgMirrorApiAdapter::view));
    }

    private static OrgMirror view(OrgEventPayload o) {
        return new OrgMirror(o.orgId(), o.kind(), o.primaryKind(), o.legalName(), o.displayName(), o.country(),
                o.status(), o.ownerUserIds(), o.applicantUserId(), o.occurredAt());
    }
}
