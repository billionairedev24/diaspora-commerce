package com.afrotransact.content.adapter.out.persistence;

import java.time.Instant;
import java.util.List;

import com.afrotransact.content.domain.site.AppLinks;
import com.afrotransact.content.domain.site.Contacts;
import com.afrotransact.content.domain.site.IdentityProviderEntry;
import com.afrotransact.content.domain.site.LocaleOption;
import com.afrotransact.content.domain.site.MarketDefault;
import com.afrotransact.content.domain.site.SiteConfig;
import com.afrotransact.content.domain.site.SocialLink;

/** JSON document stored per site-config revision (revision metadata lives in columns). */
record SiteDocument(List<SocialLink> socials, Contacts contacts, AppLinks appLinks, List<String> paymentMethods,
                    String smsProgramName, List<IdentityProviderEntry> identityProviders, List<LocaleOption> locales,
                    MarketDefault market) {

    static SiteDocument of(SiteConfig c) {
        return new SiteDocument(c.socials(), c.contacts(), c.appLinks(), c.paymentMethods(), c.smsProgramName(),
                c.identityProviders(), c.locales(), c.market());
    }

    SiteConfig toConfig(int revision, Instant at, String by) {
        return new SiteConfig(revision, socials, contacts, appLinks, paymentMethods, smsProgramName, identityProviders,
                locales, market, at, by);
    }
}
