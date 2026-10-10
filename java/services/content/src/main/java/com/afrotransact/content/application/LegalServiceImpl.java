package com.afrotransact.content.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.content.domain.legal.Heading;
import com.afrotransact.content.domain.legal.LegalDocument;
import com.afrotransact.content.domain.legal.LegalDocument.NewVersion;
import com.afrotransact.content.domain.legal.LegalDocument.Raise;
import com.afrotransact.content.domain.legal.LegalDocumentType;
import com.afrotransact.content.domain.legal.LegalTemplate;
import com.afrotransact.content.domain.legal.LegalVersion;
import com.afrotransact.content.domain.legal.LegalVersion.LocalizedBody;
import com.afrotransact.content.domain.legal.SemanticVersion;
import com.afrotransact.content.domain.site.SiteConfig;
import com.afrotransact.content.port.LegalRepository;
import com.afrotransact.content.port.SiteConfigRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LegalServiceImpl implements LegalService {

    private static final String ENTITY = "legal";

    private final LegalRepository legal;
    private final SiteConfigRepository configs;
    private final ContentAccess access;
    private final AuditTrail audit;
    private final DomainEventPublisher events;
    private final ContentSettings settings;
    private final Clock clock;

    LegalServiceImpl(LegalRepository legal, SiteConfigRepository configs, ContentAccess access, AuditTrail audit,
                     DomainEventPublisher events, ContentSettings settings, Clock clock) {
        this.legal = legal;
        this.configs = configs;
        this.access = access;
        this.audit = audit;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LegalSummary> summaries() {
        return legal.allCurrent().stream().sorted(Comparator.comparing(v -> v.type().ordinal()))
                .map(v -> LegalSummary.of(v, settings.legalBaseHref())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public LegalPage current(LegalDocumentType type, String locale) {
        return page(currentOf(type), locale);
    }

    @Override
    @Transactional(readOnly = true)
    public LegalPage version(LegalDocumentType type, String version, String locale) {
        LegalVersion v = legal.version(type, SemanticVersion.parse(version)).orElseThrow(() -> new NotFoundException(
                "content.legal-version-not-found", type.title() + " has no version " + version));
        return page(v, locale);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VersionRef> history(LegalDocumentType type) {
        return legal.versions(type).stream().map(LegalServiceImpl::ref).toList();
    }

    @Override
    @Transactional
    public LegalVersion raise(LegalDocumentType type, NewVersion draft) {
        Actor actor = access.require(ContentPermissions.LEGAL_EDIT);
        LegalVersion current = currentOf(type);
        Raise raised = new LegalDocument(type, current)
                .raise(UUID.randomUUID().toString(), draft, actor.subject(), Instant.now(clock));
        legal.update(raised.archived());
        legal.insert(raised.created());
        audit.record(actor.subject(), "legal.version-raised", ENTITY, type.key(), Map.of(
                "from", current.version().toString(), "to", raised.created().version().toString(),
                "effectiveDate", String.valueOf(raised.created().effectiveDate())));
        publish(ContentEvents.LEGAL_VERSION_PUBLISHED, raised.created(), current.version().toString());
        return raised.created();
    }

    @Override
    @Transactional
    public LegalVersion amend(LegalDocumentType type, Boolean draftBanner, LocalDate effectiveDate,
                              Map<String, String> bodies) {
        Actor actor = access.require(ContentPermissions.LEGAL_EDIT);
        LegalVersion current = currentOf(type);
        LegalVersion amended = new LegalDocument(type, current).amend(draftBanner, effectiveDate, bodies);
        legal.update(amended);
        Map<String, Object> detail = new HashMap<>();
        detail.put("version", amended.version().toString());
        detail.put("draftBanner", amended.draftBanner());
        detail.put("effectiveDate", String.valueOf(amended.effectiveDate()));
        detail.put("locales", amended.bodies().keySet());
        audit.record(actor.subject(), "legal.version-amended", ENTITY, type.key(), detail);
        publish(ContentEvents.LEGAL_VERSION_AMENDED, amended, null);
        return amended;
    }

    private void publish(String type, LegalVersion v, String previous) {
        events.publish(type, ContentEvents.AGGREGATE, v.type().key(),
                new ContentEventPayloads.LegalVersionChanged(v.type().key(), v.type().title(), v.version().toString(),
                        previous, v.effectiveDate(), v.draftBanner(), settings.legalBaseHref() + v.type().key()));
    }

    private LegalPage page(LegalVersion v, String locale) {
        LocalizedBody raw = v.body(locale);
        String rendered = LegalTemplate.render(raw.markdown(), placeholders());
        LocalizedBody body = new LocalizedBody(raw.locale(), rendered, raw.binding(), raw.fallback());
        return new LegalPage(v, body, Heading.outline(rendered), history(v.type()),
                settings.legalBaseHref() + v.type().key());
    }

    /** Values for {phone}, {privacy}, {legal}, {address}, {smsProgramName}… taken from the live site config. */
    private Map<String, String> placeholders() {
        Map<String, String> values = new HashMap<>();
        configs.latest().ifPresent((SiteConfig c) -> {
            values.putAll(c.contacts().emails());
            values.put("phone", c.contacts().supportPhoneDisplay());
            values.put("phoneE164", c.contacts().supportPhone());
            values.put("address", c.contacts().address().singleLine());
            values.put("smsProgramName", c.smsProgramName());
        });
        return values;
    }

    private LegalVersion currentOf(LegalDocumentType type) {
        return legal.current(type).orElseThrow(() -> new NotFoundException("content.legal-not-found",
                type.title() + " has not been published"));
    }

    private static VersionRef ref(LegalVersion v) {
        return new VersionRef(v.version().toString(), v.effectiveDate(), v.status(), v.archivedAt(), v.changeSummary());
    }
}
