package com.afrotransact.content.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.content.domain.site.AppLinks;
import com.afrotransact.content.domain.site.Contacts;
import com.afrotransact.content.domain.site.DefaultSiteConfigPolicy;
import com.afrotransact.content.domain.site.IdentityProviderEntry;
import com.afrotransact.content.domain.site.LocaleOption;
import com.afrotransact.content.domain.site.MarketDefault;
import com.afrotransact.content.domain.site.PostalAddress;
import com.afrotransact.content.domain.site.SiteConfig;
import com.afrotransact.content.domain.site.SocialLink;
import com.afrotransact.content.domain.site.SocialNetwork;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SiteConfigTest {

    private final DefaultSiteConfigPolicy policy = new DefaultSiteConfigPolicy();

    @ParameterizedTest
    @CsvSource({
        "TIKTOK, https://www.tiktok.com/@afrotransact, true",
        "TIKTOK, https://www.tiktok.com/afrotransact, false",
        "INSTAGRAM, https://instagram.com/afro.transact/, true",
        "INSTAGRAM, http://www.instagram.com/afrotransact, false",
        "INSTAGRAM, https://www.instagram.com.evil.io/afrotransact, false",
        "FACEBOOK, https://www.facebook.com/afrotransact, true",
        "X, https://x.com/afrotransact, true",
        "X, https://twitter.com/afrotransact, true",
        "X, https://x.com/afro-transact, false",
        "YOUTUBE, https://www.youtube.com/@afrotransact, true",
        "YOUTUBE, https://www.youtube.com/channel/UC123-abc, true",
        "YOUTUBE, https://youtu.be/abc, false",
        "LINKEDIN, https://www.linkedin.com/company/afrotransact, true",
        "LINKEDIN, https://www.linkedin.com/in/someone, false",
        "WHATSAPP, https://www.whatsapp.com/channel/0029VaAfroTransact, true",
        "WHATSAPP, https://wa.me/18335550199, false"
    })
    void profileUrlsAreValidatedPerNetwork(SocialNetwork network, String url, boolean valid) {
        assertThat(network.accepts(url)).isEqualTo(valid);
    }

    @Test
    void defaultsAreValid() {
        policy.validate(valid());
        assertThat(valid().visibleSocials()).extracting(SocialLink::network)
                .containsExactly(SocialNetwork.INSTAGRAM, SocialNetwork.X);
    }

    @Test
    void aBadProfileLinkBlocksSaving() {
        List<SocialLink> socials = new ArrayList<>(valid().socials());
        socials.set(0, new SocialLink(SocialNetwork.INSTAGRAM, "https://www.facebook.com/afrotransact", true, 1));
        assertThatThrownBy(() -> policy.validate(with(socials)))
                .isInstanceOfSatisfying(ContentRuleViolation.class, ex -> {
                    assertThat(ex.code()).isEqualTo("content.invalid-site-config");
                    assertThat(ex.fieldErrors()).containsOnlyKeys("socials.instagram");
                    assertThat(ex.fieldErrors().get("socials.instagram")).contains("Instagram");
                });
    }

    @Test
    void hiddenNetworksStillNeedAValidLinkIfOneIsGiven() {
        List<SocialLink> socials = new ArrayList<>(valid().socials());
        socials.add(new SocialLink(SocialNetwork.TIKTOK, "not a url", false, 9));
        assertThatThrownBy(() -> policy.validate(with(socials))).isInstanceOf(ContentRuleViolation.class);
        socials.set(socials.size() - 1, new SocialLink(SocialNetwork.TIKTOK, "", false, 9));
        policy.validate(with(socials));
        socials.set(socials.size() - 1, new SocialLink(SocialNetwork.TIKTOK, "", true, 9));
        assertThatThrownBy(() -> policy.validate(with(socials))).isInstanceOf(ContentRuleViolation.class);
    }

    @Test
    void contactsAndLocalesAreChecked() {
        SiteConfig base = valid();
        Map<String, String> emails = new HashMap<>(base.contacts().emails());
        emails.put("press", "press-at-afrotransact");
        SiteConfig bad = new SiteConfig(1, base.socials(), new Contacts("8335550199", "", emails,
                base.contacts().address()), base.appLinks(), base.paymentMethods(), "AT", base.identityProviders(),
                List.of(new LocaleOption("fr", "Français", false)), base.market(), null, null);
        assertThatThrownBy(() -> policy.validate(bad)).isInstanceOfSatisfying(ContentRuleViolation.class,
                ex -> assertThat(ex.fieldErrors()).containsKeys("contacts.supportPhone", "contacts.supportPhoneDisplay",
                        "contacts.emails.press", "smsProgramName", "locales"));
    }

    @Test
    void changedSectionsAndRevisions() {
        SiteConfig before = valid();
        SiteConfig after = new SiteConfig(0, before.socials(), before.contacts(), before.appLinks(),
                before.paymentMethods(), "AfroTransact Texts", before.identityProviders(), before.locales(),
                before.market(), null, null).asRevision(2, "maya", Instant.EPOCH);
        assertThat(after.revision()).isEqualTo(2);
        assertThat(after.updatedBy()).isEqualTo("maya");
        assertThat(after.changedSections(before)).containsExactly("smsProgramName");
        assertThat(before.contacts().address().singleLine())
                .isEqualTo("AfroTransact, Inc. · 1001 Texas Ave, Suite 1400 · Houston, TX 77002");
    }

    static SiteConfig valid() {
        return with(List.of(
                new SocialLink(SocialNetwork.INSTAGRAM, "https://www.instagram.com/afrotransact", true, 1),
                new SocialLink(SocialNetwork.X, "https://x.com/afrotransact", true, 2),
                new SocialLink(SocialNetwork.WHATSAPP, "https://www.whatsapp.com/channel/0029VaAfroTransact", false, 3)));
    }

    private static SiteConfig with(List<SocialLink> socials) {
        Map<String, String> emails = new HashMap<>();
        for (String purpose : Contacts.REQUIRED_EMAILS) {
            emails.put(purpose, purpose + "@afrotransact.com");
        }
        return new SiteConfig(1, socials, new Contacts("+18335550199", "+1 (833) 555-0199", emails,
                new PostalAddress("AfroTransact, Inc.", "1001 Texas Ave", "Suite 1400", "Houston", "TX", "77002", "US")),
                new AppLinks(null, null), List.of("VISA", "Apple Pay"), "AfroTransact Alerts",
                List.of(new IdentityProviderEntry("google", "Google", 1)),
                List.of(new LocaleOption("en", "English", false), new LocaleOption("ar", "العربية", true)),
                new MarketDefault("US", "USD", "US"), Instant.EPOCH, "seed");
    }
}
