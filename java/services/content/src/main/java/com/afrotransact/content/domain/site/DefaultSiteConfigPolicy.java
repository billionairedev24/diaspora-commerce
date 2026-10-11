package com.afrotransact.content.domain.site;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.afrotransact.content.domain.ContentRuleViolation;
import com.afrotransact.content.domain.EmailAddress;

/** Default {@link SiteConfigPolicy}. */
public class DefaultSiteConfigPolicy implements SiteConfigPolicy {

    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{6,14}$");
    private static final Pattern LOCALE = Pattern.compile("^[a-z]{2}(-[A-Z]{2})?$");
    private static final Pattern PROVIDER = Pattern.compile("^[a-z][a-z0-9-]{1,30}$");
    private static final Pattern IOS = Pattern.compile("^https://apps\\.apple\\.com/.+$");
    private static final Pattern ANDROID = Pattern.compile("^https://play\\.google\\.com/store/apps/details\\?id=[\\w.]+.*$");
    private static final Pattern ISO2 = Pattern.compile("^[A-Z]{2}$");
    private static final Pattern ISO3 = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern WHATSAPP_COMMUNITY = Pattern.compile(
            "^https://(chat\\.whatsapp\\.com/(invite/)?[A-Za-z0-9]{6,}|(www\\.)?whatsapp\\.com/channel/[A-Za-z0-9]+)/?$");

    @Override
    public void validate(SiteConfig c) {
        Map<String, String> errors = new LinkedHashMap<>();
        socials(c.socials(), errors);
        contacts(c.contacts(), errors);
        if (c.appLinks().ios() != null && !IOS.matcher(c.appLinks().ios()).matches()) {
            errors.put("appLinks.ios", "Use the full https://apps.apple.com/… link");
        }
        if (c.appLinks().android() != null && !ANDROID.matcher(c.appLinks().android()).matches()) {
            errors.put("appLinks.android", "Use the full https://play.google.com/store/apps/details?id=… link");
        }
        if (c.paymentMethods().isEmpty() || new HashSet<>(c.paymentMethods()).size() != c.paymentMethods().size()
                || c.paymentMethods().stream().anyMatch(p -> p == null || p.isBlank() || p.length() > 30)) {
            errors.put("paymentMethods", "List each payment method once (up to 30 characters each)");
        }
        String sms = c.smsProgramName();
        if (sms == null || sms.trim().length() < 3 || sms.length() > 60) {
            errors.put("smsProgramName", "Give the SMS program a name of 3 to 60 characters");
        }
        if (c.whatsappCommunityUrl() != null && !WHATSAPP_COMMUNITY.matcher(c.whatsappCommunityUrl()).matches()) {
            errors.put("whatsappCommunityUrl", "Use the community's invite link, like https://chat.whatsapp.com/AbCdEf123456,"
                    + " or a https://whatsapp.com/channel/… link");
        }
        providers(c.identityProviders(), errors);
        locales(c.locales(), errors);
        if (c.market() == null || !ISO2.matcher(nz(c.market().country())).matches()
                || !ISO3.matcher(nz(c.market().currency())).matches() || nz(c.market().code()).isBlank()) {
            errors.put("market", "Market needs a code, an ISO country and an ISO currency");
        }
        ContentRuleViolation.throwIfAny("content.invalid-site-config", "The site configuration is invalid", errors);
    }

    private static void socials(List<SocialLink> socials, Map<String, String> errors) {
        Set<SocialNetwork> seen = new HashSet<>();
        for (SocialLink s : socials) {
            String field = "socials." + s.network().key();
            if (!seen.add(s.network())) {
                errors.put(field, "Each network can appear once");
            } else if (s.visible() && (s.url() == null || s.url().isBlank())) {
                errors.put(field, "Add the profile link or hide " + s.network().label());
            } else if (s.url() != null && !s.url().isBlank() && !s.network().accepts(s.url())) {
                errors.put(field, "Fix the link for " + s.network().label() + " — it must be a full https:// profile URL"
                        + " on that network, like " + s.network().example());
            }
        }
    }

    private static void contacts(Contacts c, Map<String, String> errors) {
        if (c == null) {
            errors.put("contacts", "Required");
            return;
        }
        if (!E164.matcher(nz(c.supportPhone())).matches()) {
            errors.put("contacts.supportPhone", "Use the international E.164 format, for example +18335550199");
        }
        if (nz(c.supportPhoneDisplay()).isBlank()) {
            errors.put("contacts.supportPhoneDisplay", "Required");
        }
        for (String purpose : Contacts.REQUIRED_EMAILS) {
            if (!EmailAddress.valid(nz(c.email(purpose)).trim().toLowerCase(Locale.ROOT))) {
                errors.put("contacts.emails." + purpose, "Enter a valid email address");
            }
        }
        c.emails().forEach((purpose, email) -> {
            if (!EmailAddress.valid(nz(email).trim().toLowerCase(Locale.ROOT))) {
                errors.put("contacts.emails." + purpose, "Enter a valid email address");
            }
        });
        PostalAddress a = c.address();
        if (a == null || nz(a.name()).isBlank() || nz(a.line1()).isBlank() || nz(a.city()).isBlank()
                || nz(a.region()).isBlank() || nz(a.postalCode()).isBlank() || !ISO2.matcher(nz(a.country())).matches()) {
            errors.put("contacts.address", "Company name, street, city, state, ZIP and country are required");
        }
    }

    private static void providers(List<IdentityProviderEntry> providers, Map<String, String> errors) {
        Set<String> ids = new HashSet<>();
        for (IdentityProviderEntry p : providers) {
            if (!PROVIDER.matcher(nz(p.id())).matches() || nz(p.name()).isBlank() || !ids.add(p.id())) {
                errors.put("identityProviders", "Each provider needs a unique lower-case id and a name");
            }
        }
    }

    private static void locales(List<LocaleOption> locales, Map<String, String> errors) {
        Set<String> codes = new HashSet<>();
        for (LocaleOption l : locales) {
            if (!LOCALE.matcher(nz(l.code())).matches() || nz(l.name()).isBlank() || !codes.add(l.code())) {
                errors.put("locales", "Each language needs a unique code (en, fr-CA) and a name");
            }
        }
        if (!codes.contains("en")) {
            errors.put("locales", "English must stay available: it is the binding language of the legal documents");
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
