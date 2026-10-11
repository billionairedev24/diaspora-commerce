package com.afrotransact.content.domain.site;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Runtime-editable site configuration. Every save creates a new revision; nothing here needs a deployment.
 *
 * @param revision          monotonically increasing revision
 * @param socials           social links (including hidden ones)
 * @param contacts          phone, emails and address
 * @param appLinks          store links
 * @param paymentMethods    payment method chips in the footer
 * @param smsProgramName    SMS program name used in legal text and opt-in disclosures
 * @param identityProviders sign-in button order (enabled flags come from identity-service)
 * @param locales           languages offered
 * @param market            default market
 * @param updatedAt         when this revision was saved
 * @param updatedBy         who saved it
 * @param whatsappCommunityUrl invitation to the WhatsApp community (chat.whatsapp.com invite or whatsapp.com
 *                          channel), shown in every email footer and on the site; null when there is none
 */
public record SiteConfig(int revision, List<SocialLink> socials, Contacts contacts, AppLinks appLinks,
                         List<String> paymentMethods, String smsProgramName,
                         List<IdentityProviderEntry> identityProviders, List<LocaleOption> locales,
                         MarketDefault market, Instant updatedAt, String updatedBy, String whatsappCommunityUrl) {

    /** A configuration without a WhatsApp community link. */
    public SiteConfig(int revision, List<SocialLink> socials, Contacts contacts, AppLinks appLinks,
                      List<String> paymentMethods, String smsProgramName,
                      List<IdentityProviderEntry> identityProviders, List<LocaleOption> locales,
                      MarketDefault market, Instant updatedAt, String updatedBy) {
        this(revision, socials, contacts, appLinks, paymentMethods, smsProgramName, identityProviders, locales, market,
                updatedAt, updatedBy, null);
    }

    public SiteConfig {
        whatsappCommunityUrl = whatsappCommunityUrl == null || whatsappCommunityUrl.isBlank() ? null
                : whatsappCommunityUrl.trim();
        socials = socials == null ? List.of() : socials.stream().sorted(Comparator.comparingInt(SocialLink::order)).toList();
        paymentMethods = paymentMethods == null ? List.of() : List.copyOf(paymentMethods);
        identityProviders = identityProviders == null ? List.of()
                : identityProviders.stream().sorted(Comparator.comparingInt(IdentityProviderEntry::order)).toList();
        locales = locales == null ? List.of() : List.copyOf(locales);
        appLinks = appLinks == null ? new AppLinks(null, null) : appLinks;
    }

    /** Visible links whose URL passes the network rule, in display order. */
    public List<SocialLink> visibleSocials() {
        return socials.stream().filter(SocialLink::visible).filter(s -> s.network().accepts(s.url())).toList();
    }

    /** The same content as a new revision. */
    public SiteConfig asRevision(int next, String actor, Instant now) {
        return new SiteConfig(next, socials, contacts, appLinks, paymentMethods, smsProgramName, identityProviders,
                locales, market, now, actor, whatsappCommunityUrl);
    }

    /** Names of the sections that differ from {@code previous} (for audit and the change event). */
    public List<String> changedSections(SiteConfig previous) {
        List<String> out = new ArrayList<>();
        if (previous == null) {
            return List.of("socials", "contacts", "appLinks", "paymentMethods", "smsProgramName", "identityProviders",
                    "locales", "market", "whatsappCommunityUrl");
        }
        add(out, "socials", socials, previous.socials);
        add(out, "contacts", contacts, previous.contacts);
        add(out, "appLinks", appLinks, previous.appLinks);
        add(out, "paymentMethods", paymentMethods, previous.paymentMethods);
        add(out, "smsProgramName", smsProgramName, previous.smsProgramName);
        add(out, "identityProviders", identityProviders, previous.identityProviders);
        add(out, "locales", locales, previous.locales);
        add(out, "market", market, previous.market);
        add(out, "whatsappCommunityUrl", whatsappCommunityUrl, previous.whatsappCommunityUrl);
        return out;
    }

    private static void add(List<String> out, String name, Object now, Object before) {
        if (!Objects.equals(now, before)) {
            out.add(name);
        }
    }
}
