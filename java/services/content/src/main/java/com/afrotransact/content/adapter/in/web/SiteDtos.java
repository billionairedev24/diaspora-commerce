package com.afrotransact.content.adapter.in.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.afrotransact.content.application.LegalSummary;
import com.afrotransact.content.application.SiteConfigService.PublishedSiteConfig;
import com.afrotransact.content.domain.site.AppLinks;
import com.afrotransact.content.domain.site.Contacts;
import com.afrotransact.content.domain.site.IdentityProviderEntry;
import com.afrotransact.content.domain.site.LocaleOption;
import com.afrotransact.content.domain.site.MarketDefault;
import com.afrotransact.content.domain.site.PostalAddress;
import com.afrotransact.content.domain.site.SiteConfig;
import com.afrotransact.content.domain.site.SocialLink;
import com.afrotransact.content.domain.site.SocialNetwork;
import com.afrotransact.content.port.SiteConfigRepository.RevisionSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Site configuration bodies. The public shape matches the BFF's SiteConfig schema. */
final class SiteDtos {

    private SiteDtos() {
    }

    @Schema(name = "SiteSocial")
    record SocialDto(@Schema(example = "instagram") String network, String url, String label) {
    }

    @Schema(name = "SiteContacts")
    record ContactsDto(String phone, String phoneE164, String supportEmail, String privacyEmail,
                       String accessibilityEmail, String legalEmail, String pressEmail, String partnersEmail,
                       String careersEmail, String address, AddressDto postalAddress) {

        static ContactsDto of(Contacts c) {
            return new ContactsDto(c.supportPhoneDisplay(), c.supportPhone(), c.email("support"), c.email("privacy"),
                    c.email("accessibility"), c.email("legal"), c.email("press"), c.email("partners"),
                    c.email("careers"), c.address().singleLine(), AddressDto.of(c.address()));
        }
    }

    @Schema(name = "SiteAddress")
    record AddressDto(String name, String line1, String line2, String city, String region, String postalCode,
                      String country) {

        static AddressDto of(PostalAddress a) {
            return new AddressDto(a.name(), a.line1(), a.line2(), a.city(), a.region(), a.postalCode(), a.country());
        }

        PostalAddress toDomain() {
            return new PostalAddress(name, line1, line2, city, region, postalCode, country);
        }
    }

    @Schema(name = "SiteLegalDocument")
    record LegalDto(String key, String title, String version, LocalDate effective, String href, boolean draft) {

        static LegalDto of(LegalSummary s) {
            return new LegalDto(s.key(), s.title(), s.version(), s.effectiveDate(), s.href(), s.draftBanner());
        }
    }

    @Schema(name = "SiteIdentityProvider", description = "Button order; whether a provider is enabled comes from identity-service")
    record IdentityProviderDto(String id, String name, Integer order) {

        static IdentityProviderDto of(IdentityProviderEntry e) {
            return new IdentityProviderDto(e.id(), e.name(), e.order());
        }

        IdentityProviderEntry toDomain() {
            return new IdentityProviderEntry(id, name, order == null ? 0 : order);
        }
    }

    @Schema(name = "SiteLocale")
    record LocaleDto(String code, String name, @Schema(allowableValues = {"ltr", "rtl"}) String dir) {

        static LocaleDto of(LocaleOption l) {
            return new LocaleDto(l.code(), l.name(), l.rtl() ? "rtl" : "ltr");
        }

        LocaleOption toDomain() {
            return new LocaleOption(code, name, "rtl".equalsIgnoreCase(dir));
        }
    }

    @Schema(name = "SiteMarket")
    record MarketDto(String code, String currency, String country) {

        static MarketDto of(MarketDefault m) {
            return new MarketDto(m.code(), m.currency(), m.country());
        }

        MarketDefault toDomain() {
            return new MarketDefault(code, currency, country);
        }
    }

    @Schema(name = "SiteAppLinks")
    record AppLinksDto(String ios, String android) {

        static AppLinksDto of(AppLinks a) {
            return new AppLinksDto(a.ios(), a.android());
        }
    }

    @Schema(name = "SiteConfig", description = "Public site configuration (visible socials only)")
    record PublicSiteConfig(int revision, List<SocialDto> socials, ContactsDto contacts, List<LegalDto> legal,
                            List<IdentityProviderDto> identityProviders, List<LocaleDto> locales, MarketDto market,
                            AppLinksDto appLinks, List<String> paymentMethods, String smsProgramName,
                            Instant updatedAt) {

        static PublicSiteConfig of(PublishedSiteConfig p) {
            SiteConfig c = p.config();
            return new PublicSiteConfig(c.revision(),
                    c.visibleSocials().stream().map(s -> new SocialDto(s.network().key(), s.url(),
                            s.network().label())).toList(),
                    ContactsDto.of(c.contacts()), p.legal().stream().map(LegalDto::of).toList(),
                    c.identityProviders().stream().map(IdentityProviderDto::of).toList(),
                    c.locales().stream().map(LocaleDto::of).toList(), MarketDto.of(c.market()),
                    AppLinksDto.of(c.appLinks()), c.paymentMethods(), c.smsProgramName(), c.updatedAt());
        }
    }

    @Schema(name = "SiteSocialLink")
    record SocialLinkDto(@Schema(example = "instagram") String network, String url, Boolean visible, Integer order) {

        static SocialLinkDto of(SocialLink s) {
            return new SocialLinkDto(s.network().key(), s.url(), s.visible(), s.order());
        }

        SocialLink toDomain() {
            return new SocialLink(SocialNetwork.fromKey(network), url == null ? null : url.trim(), Boolean.TRUE.equals(visible),
                    order == null ? 0 : order);
        }
    }

    @Schema(name = "SiteContactsSettings")
    record ContactSettingsDto(@Schema(example = "+18335550199") String supportPhone,
                              @Schema(example = "+1 (833) 555-0199") String supportPhoneDisplay,
                              @Schema(description = "Email per purpose: support, privacy, accessibility, legal, press, "
                                      + "partners, careers") Map<String, String> emails,
                              AddressDto address) {

        static ContactSettingsDto of(Contacts c) {
            return new ContactSettingsDto(c.supportPhone(), c.supportPhoneDisplay(), c.emails(), AddressDto.of(c.address()));
        }

        Contacts toDomain() {
            return new Contacts(supportPhone, supportPhoneDisplay, emails, address == null ? null : address.toDomain());
        }
    }

    @Schema(name = "SiteConfigSettings", description = "Full, editable site configuration (back office)")
    record SiteConfigSettings(int revision, List<SocialLinkDto> socials, ContactSettingsDto contacts,
                              AppLinksDto appLinks, List<String> paymentMethods, String smsProgramName,
                              List<IdentityProviderDto> identityProviders, List<LocaleDto> locales, MarketDto market,
                              Instant updatedAt, String updatedBy) {

        static SiteConfigSettings of(SiteConfig c) {
            return new SiteConfigSettings(c.revision(), c.socials().stream().map(SocialLinkDto::of).toList(),
                    ContactSettingsDto.of(c.contacts()), AppLinksDto.of(c.appLinks()), c.paymentMethods(),
                    c.smsProgramName(), c.identityProviders().stream().map(IdentityProviderDto::of).toList(),
                    c.locales().stream().map(LocaleDto::of).toList(), MarketDto.of(c.market()), c.updatedAt(),
                    c.updatedBy());
        }
    }

    @Schema(name = "UpdateSiteConfigRequest")
    record UpdateSiteConfigRequest(@NotNull @Schema(description = "Revision the editor loaded") Integer expectedRevision,
                                   String summary, List<SocialLinkDto> socials, ContactSettingsDto contacts,
                                   AppLinksDto appLinks, List<String> paymentMethods, String smsProgramName,
                                   List<IdentityProviderDto> identityProviders, List<LocaleDto> locales,
                                   MarketDto market) {

        SiteConfig toDomain() {
            return new SiteConfig(0, socials == null ? List.of() : socials.stream().map(SocialLinkDto::toDomain).toList(),
                    contacts == null ? null : contacts.toDomain(),
                    appLinks == null ? null : new AppLinks(blankToNull(appLinks.ios()), blankToNull(appLinks.android())),
                    paymentMethods, smsProgramName == null ? null : smsProgramName.trim(),
                    identityProviders == null ? List.of()
                            : identityProviders.stream().map(IdentityProviderDto::toDomain).toList(),
                    locales == null ? List.of() : locales.stream().map(LocaleDto::toDomain).toList(),
                    market == null ? null : market.toDomain(), null, null);
        }

        private static String blankToNull(String s) {
            return s == null || s.isBlank() ? null : s.trim();
        }
    }

    @Schema(name = "SiteConfigRevision")
    record RevisionDto(int revision, String createdBy, Instant createdAt, String summary) {

        static RevisionDto of(RevisionSummary r) {
            return new RevisionDto(r.revision(), r.createdBy(), r.createdAt(), r.summary());
        }
    }
}
