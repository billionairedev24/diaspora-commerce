package com.afrotransact.content.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.afrotransact.content.domain.ContentRuleViolation;
import com.afrotransact.content.domain.legal.ConsentPolicy;
import com.afrotransact.content.domain.legal.ConsentRecord;
import com.afrotransact.content.domain.legal.CookieCategory;
import com.afrotransact.content.domain.legal.CookieDefinition;
import com.afrotransact.content.domain.legal.LegalDocumentType;
import com.afrotransact.content.domain.legal.LegalVersion;
import com.afrotransact.content.port.CookieRepository;
import com.afrotransact.content.port.LegalRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CookieConsentServiceImpl implements CookieConsentService {

    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9-]{1,29}$");
    private static final Pattern SUBJECT = Pattern.compile("^[A-Za-z0-9._:@-]{6,120}$");
    private static final Set<String> SOURCES = Set.of("banner", "preferences", "privacy-choices", "app", "gpc");

    private final CookieRepository cookies;
    private final LegalRepository legal;
    private final ContentAccess access;
    private final AuditTrail audit;
    private final ContentSettings settings;
    private final Clock clock;

    CookieConsentServiceImpl(CookieRepository cookies, LegalRepository legal, ContentAccess access, AuditTrail audit,
                             ContentSettings settings, Clock clock) {
        this.cookies = cookies;
        this.legal = legal;
        this.access = access;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public CookiePolicy policy() {
        LegalVersion notice = notice();
        List<CookieCategory> categories = cookies.categories();
        List<CookieDefinition> table = cookies.cookies();
        return new CookiePolicy(notice.version().toString(), notice.effectiveDate(),
                settings.legalBaseHref() + LegalDocumentType.COOKIES.key(), categories, table,
                Fingerprint.of(notice.version(), categories, table));
    }

    @Override
    @Transactional
    public ConsentRecord record(String subject, Map<String, Boolean> choices, boolean gpc, boolean saleOptOut,
                                String source) {
        if (subject == null || !SUBJECT.matcher(subject).matches()) {
            throw new ContentRuleViolation("content.invalid-consent", "subject must be the device or user id",
                    Map.of("subject", "6-120 letters, digits or . _ : @ -"));
        }
        String src = source == null || source.isBlank() ? "banner" : source.trim();
        if (!SOURCES.contains(src)) {
            throw new ContentRuleViolation("content.invalid-consent", "Unknown source " + source,
                    Map.of("source", "One of " + SOURCES));
        }
        boolean optOut = saleOptOut || gpc;
        Map<String, Boolean> normalised = ConsentPolicy.normalise(choices, cookies.categories(), optOut);
        ConsentRecord rec = new ConsentRecord(UUID.randomUUID(), subject, normalised, gpc, optOut,
                notice().version().toString(), src, Instant.now(clock));
        cookies.insertConsent(rec);
        return rec;
    }

    @Override
    @Transactional
    public CookieCategory saveCategory(CookieCategory category) {
        Actor actor = access.require(ContentPermissions.LEGAL_EDIT);
        if (category.key() == null || !KEY.matcher(category.key()).matches() || blank(category.name())
                || blank(category.description())) {
            throw new ContentRuleViolation("content.invalid-cookie-category", "The category is invalid",
                    Map.of("key", "Lower-case key, name and description required"));
        }
        cookies.saveCategory(category);
        audit.record(actor.subject(), "cookie.category-saved", "cookie", category.key(),
                Map.of("required", category.required(), "gpcOptOut", category.gpcOptOut()));
        return category;
    }

    @Override
    @Transactional
    public CookieDefinition saveCookie(CookieDefinition cookie) {
        Actor actor = access.require(ContentPermissions.LEGAL_EDIT);
        if (blank(cookie.name()) || blank(cookie.provider()) || blank(cookie.purpose()) || blank(cookie.duration())) {
            throw new ContentRuleViolation("content.invalid-cookie", "Name, provider, purpose and duration are required",
                    Map.of("cookie", "Incomplete"));
        }
        if (cookies.categories().stream().noneMatch(c -> c.key().equals(cookie.category()))) {
            throw new ContentRuleViolation("content.unknown-cookie-category", "Unknown category " + cookie.category(),
                    Map.of("category", "Unknown"));
        }
        cookies.saveCookie(cookie);
        audit.record(actor.subject(), "cookie.saved", "cookie", cookie.name(), Map.of("category", cookie.category()));
        return cookie;
    }

    @Override
    @Transactional
    public void deleteCookie(String name) {
        Actor actor = access.require(ContentPermissions.LEGAL_EDIT);
        if (!cookies.deleteCookie(name)) {
            throw new NotFoundException("content.cookie-not-found", "No cookie " + name);
        }
        audit.record(actor.subject(), "cookie.deleted", "cookie", name, Map.of());
    }

    private LegalVersion notice() {
        return legal.current(LegalDocumentType.COOKIES).orElseThrow(() -> new NotFoundException(
                "content.legal-not-found", "The Cookie Notice has not been published"));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
