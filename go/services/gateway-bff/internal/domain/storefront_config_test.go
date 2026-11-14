package domain

import (
	"strings"
	"testing"
)

var testPolicy = StorePolicy{MediaPrefixes: []string{"https://media.example.com/", "http://localhost:9000/media/"}}

func validConfig() StoreConfig {
	c := DefaultStoreConfig()
	c.Hero.Tagline = "Hand-made leather, Lagos to Houston"
	c.About.Body = "Line one.\n\nLine two."
	return c
}

func TestDefaultConfigIsValid(t *testing.T) {
	if _, errs := ValidateStoreConfig(DefaultStoreConfig(), testPolicy); len(errs) != 0 {
		t.Fatalf("default config must be valid: %v", errs)
	}
}

func TestAccentContrastIsEnforcedPerPreset(t *testing.T) {
	cases := []struct {
		preset, accent string
		ok             bool
	}{
		{PresetClean, "#1a7f4b", true},
		{PresetClean, "#ffd400", false}, // yellow on white: 1.4:1
		{PresetClean, "#767676", true},  // 4.54:1
		{PresetClean, "#777777", false}, // 4.48:1
		{PresetMidnight, "#ffd400", true},
		{PresetMidnight, "#1a7f4b", false}, // dark green on near-black
		{PresetWarmMarket, "#b45309", true},
	}
	for _, c := range cases {
		cfg := validConfig()
		cfg.Theme = StoreTheme{Preset: c.preset, Accent: &c.accent}
		out, errs := ValidateStoreConfig(cfg, testPolicy)
		if (len(errs) == 0) != c.ok {
			t.Errorf("%s on %s: ok=%v, errors=%v", c.accent, c.preset, c.ok, errs)
		}
		if c.ok && (out.Theme.Accent == nil || *out.Theme.Accent != c.accent) {
			t.Errorf("accent not kept: %+v", out.Theme)
		}
	}
}

func TestContrastKnownValues(t *testing.T) {
	if got := Contrast("#000000", "#ffffff"); got < 20.9 || got > 21.1 {
		t.Fatalf("black on white = %v", got)
	}
	if got := Contrast("#ffffff", "#ffffff"); got != 1 {
		t.Fatalf("same colour = %v", got)
	}
}

func TestTextIsPlainAndBounded(t *testing.T) {
	for _, bad := range []string{"<script>alert(1)</script>", "hello <b>world</b>", "<!-- x -->", "<img src=x onerror=1>"} {
		cfg := validConfig()
		cfg.About.Body = bad
		if _, errs := ValidateStoreConfig(cfg, testPolicy); errs["about.body"] == "" {
			t.Errorf("markup accepted: %q", bad)
		}
	}
	cfg := validConfig()
	cfg.About.Body = "2 < 3 and 5 > 4"
	if _, errs := ValidateStoreConfig(cfg, testPolicy); len(errs) != 0 {
		t.Errorf("plain comparison refused: %v", errs)
	}
	cfg.Hero.Tagline = strings.Repeat("a", 141)
	if _, errs := ValidateStoreConfig(cfg, testPolicy); errs["hero.tagline"] == "" {
		t.Errorf("long tagline accepted")
	}
	cfg = validConfig()
	cfg.About.Body = "a\r\n\r\n\r\n\r\nb​\x07c"
	out, errs := ValidateStoreConfig(cfg, testPolicy)
	if len(errs) != 0 || out.About.Body != "a\n\nbc" {
		t.Errorf("not normalised: %q %v", out.About.Body, errs)
	}
}

func TestImagesMustBeOurMedia(t *testing.T) {
	ok := "https://media.example.com/storefront/a.jpg"
	for u, want := range map[string]bool{
		ok: true, "http://localhost:9000/media/storefront/a.jpg": true,
		"https://evil.example.org/a.jpg": false, "https://media.example.com.evil.org/a.jpg": false,
		"javascript:alert(1)": false, "data:image/png;base64,AAAA": false, "https://media.example.com@evil.org/a.jpg": false,
		"https://media.example.com/../x.jpg": false, "//media.example.com/a.jpg": false,
	} {
		cfg := validConfig()
		cfg.Hero.BannerURL = &u
		_, errs := ValidateStoreConfig(cfg, testPolicy)
		if (errs["hero.bannerUrl"] == "") != want {
			t.Errorf("%q: allowed=%v want %v", u, errs["hero.bannerUrl"] == "", want)
		}
	}
}

func TestLinksAreHTTPSOnAllowedHosts(t *testing.T) {
	cases := []struct {
		network, url string
		ok           bool
	}{
		{"instagram", "https://www.instagram.com/laserfeet", true},
		{"instagram", "https://instagram.com.evil.org/x", false},
		{"instagram", "http://instagram.com/x", false},
		{"facebook", "https://m.facebook.com/laserfeet", true},
		{"x", "https://twitter.com/laserfeet", true},
		{"whatsapp", "https://wa.me/15551234567", true},
		{"website", "https://laserfeet.example.com/shop", true},
		{"website", "https://127.0.0.1/x", false},
		{"website", "https://localhost/x", false},
		{"website", "javascript:alert(1)", false},
		{"website", "https://user:pass@example.com/", false},
		{"myspace", "https://myspace.com/x", false},
	}
	for _, c := range cases {
		cfg := validConfig()
		cfg.Social = []StoreSocialLink{{Network: c.network, URL: c.url}}
		_, errs := ValidateStoreConfig(cfg, testPolicy)
		if (len(errs) == 0) != c.ok {
			t.Errorf("%s %s: ok=%v errors=%v", c.network, c.url, c.ok, errs)
		}
	}
}

func TestAnnouncementLinkIsOnSiteOrHTTPS(t *testing.T) {
	for h, want := range map[string]bool{"/b/laserfeet": true, "https://example.com/sale": true, "//evil.org": false, "javascript:alert(1)": false, "http://example.com": false} {
		cfg := validConfig()
		cfg.Announcement = StoreAnnouncement{Text: "Free pickup this week", Href: &h}
		_, errs := ValidateStoreConfig(cfg, testPolicy)
		if (errs["announcement.href"] == "") != want {
			t.Errorf("%q allowed=%v want %v", h, errs["announcement.href"] == "", want)
		}
	}
}

func TestSectionsAreCompletedAndDeduplicated(t *testing.T) {
	cfg := validConfig()
	cfg.Sections = []StoreSection{{ID: "about", Enabled: true}, {ID: "featured", Enabled: true}}
	out, errs := ValidateStoreConfig(cfg, testPolicy)
	if len(errs) != 0 || len(out.Sections) != 6 || out.Sections[0].ID != "about" || out.Sections[1].ID != "featured" {
		t.Fatalf("sections not completed in order: %+v %v", out.Sections, errs)
	}
	cfg.Sections = []StoreSection{{ID: "about", Enabled: true}, {ID: "about", Enabled: true}}
	if _, errs := ValidateStoreConfig(cfg, testPolicy); errs["sections"] == "" {
		t.Fatal("duplicate section accepted")
	}
	cfg.Sections = []StoreSection{{ID: "hero", Enabled: true}}
	if _, errs := ValidateStoreConfig(cfg, testPolicy); errs["sections"] == "" {
		t.Fatal("unknown section accepted")
	}
}

func TestSanitizeNeverFailsAndDropsOnlyTheBadField(t *testing.T) {
	cfg := validConfig()
	bad := "https://evil.example.org/a.jpg"
	cfg.Hero.BannerURL = &bad
	cfg.About.Body = "<script>x</script>"
	accent := "#ffd400"
	cfg.Theme.Accent = &accent
	out := SanitizeStoreConfig(cfg, testPolicy)
	if out.Hero.BannerURL != nil || out.About.Body != "" || out.Theme.Accent != nil {
		t.Fatalf("bad fields kept: %+v", out)
	}
	if out.Hero.Tagline != cfg.Hero.Tagline {
		t.Fatalf("good field dropped: %q", out.Hero.Tagline)
	}
}

func TestFeaturedIsBoundedAndShaped(t *testing.T) {
	cfg := validConfig()
	cfg.Featured = []string{"AT-1", "AT-1", "AT-2"}
	out, errs := ValidateStoreConfig(cfg, testPolicy)
	if len(errs) != 0 || len(out.Featured) != 2 {
		t.Fatalf("dedupe: %v %v", out.Featured, errs)
	}
	cfg.Featured = []string{"a b"}
	if _, errs := ValidateStoreConfig(cfg, testPolicy); errs["featured"] == "" {
		t.Fatal("bad atin accepted")
	}
	cfg.Featured = make([]string, 9)
	for i := range cfg.Featured {
		cfg.Featured[i] = "AT" + string(rune('A'+i))
	}
	if _, errs := ValidateStoreConfig(cfg, testPolicy); errs["featured"] == "" {
		t.Fatal("nine featured accepted")
	}
}
