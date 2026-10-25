package app_test

import (
	"context"
	"flag"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

var updateGolden = flag.Bool("update", false, "rewrite testdata/golden/*.html from the current templates")

// testBrand is the brand every golden render uses (what content's site config serves, plus app links).
var testBrand = domain.Brand{Name: "AfroTransact", StorefrontURL: "https://afrotransact.com", AssetBaseURL: "https://afrotransact.com/email",
	Socials: []domain.SocialLink{{Network: "tiktok", URL: "https://www.tiktok.com/@afrotransact"}, {Network: "instagram", URL: "https://www.instagram.com/afrotransact"},
		{Network: "facebook", URL: "https://www.facebook.com/afrotransact"}, {Network: "x", URL: "https://x.com/afrotransact"},
		{Network: "youtube", URL: "https://www.youtube.com/@afrotransact"}, {Network: "linkedin", URL: "https://www.linkedin.com/company/afrotransact"}},
	WhatsAppCommunityURL: "https://chat.whatsapp.com/AfroTransactCommunity", AppStoreURL: "https://apps.apple.com/app/afrotransact/id1",
	PlayStoreURL: "https://play.google.com/store/apps/details?id=com.afrotransact", SupportPhone: "+1 (833) 555-0199", SupportPhoneE164: "+18335550199",
	SupportEmail: "help@afrotransact.com", Address: "AfroTransact, Inc. · 1001 Texas Ave, Suite 1400 · Houston, TX 77002",
	Legal: []domain.BrandLink{{Label: "Privacy Notice", URL: "/legal/privacy"}, {Label: "Conditions of Use", URL: "/legal/conditions-of-use"}}}

func brandedRenderer() app.Renderer {
	return app.NewRenderer(app.WithBrand(app.StaticBrand(testBrand)),
		app.WithClock(func() time.Time { return time.Date(2026, 10, 11, 12, 0, 0, 0, time.UTC) }))
}

var (
	imgTag      = regexp.MustCompile(`<img\b[^>]*>`)
	altAttr     = regexp.MustCompile(`\balt="[^"]+"`)
	srcAttr     = regexp.MustCompile(`\bsrc="([^"]*)"`)
	linkCSS     = regexp.MustCompile(`(?i)<link\b[^>]*stylesheet|@import`)
	svgOrData   = regexp.MustCompile(`(?i)src="(data:|[^"]*\.svg)`)
	scriptTag   = regexp.MustCompile(`(?i)<script\b`)
	hrefEmpty   = regexp.MustCompile(`href=""`)
	adSection   = "Complete your pantry|Taste of home, delivered|Party jollof|Pre-order open in"
	adSectionRe = regexp.MustCompile(adSection)
)

// TestEveryEmailRendersOnTheBrandedLayout renders every seeded email template with its sample data and checks the
// S279 rules: the shared layout (logo, footer, socials, WhatsApp community), alt text on every image, hosted PNGs
// only, no external CSS or scripts, unsubscribe on promotional emails, no advertorials in security emails, under
// Gmail's ~102 KB clipping limit, and the golden file (go test ./internal/app -run Branded -update rewrites them).
func TestEveryEmailRendersOnTheBrandedLayout(t *testing.T) {
	samples, err := app.NewTemplateSamples(context.Background(), sampleLinks)
	if err != nil {
		t.Fatal(err)
	}
	r := brandedRenderer()
	dir := filepath.Join("testdata", "golden")
	if *updateGolden {
		_ = os.RemoveAll(dir)
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
	}
	emails := 0
	for _, tpl := range seededTemplates(t) {
		if tpl.Channel != domain.ChannelEmail {
			continue
		}
		emails++
		s, _ := samples.Sample(tpl.Key)
		out, err := r.Render(tpl, s.Data)
		if err != nil {
			t.Errorf("%s: %v", tpl.Key, err)
			continue
		}
		h := out.HTML
		check := func(ok bool, what string) {
			if !ok {
				t.Errorf("%s: %s", tpl.Key, what)
			}
		}
		check(!app.IsFullDocument(tpl.BodyHTML), "body is a full document, not a layout fragment")
		check(strings.Contains(h, `src="https://afrotransact.com/email/logo-header.png"`), "no hosted logo in the header")
		check(strings.Contains(h, "Follow AfroTransact"), "no social block")
		for _, n := range []string{"tiktok", "instagram", "facebook", "x", "youtube", "linkedin"} {
			check(strings.Contains(h, "email/social/"+n+".png"), "no "+n+" icon")
		}
		check(strings.Contains(h, "Join our WhatsApp community") && strings.Contains(h, testBrand.WhatsAppCommunityURL), "no WhatsApp community link")
		check(strings.Contains(h, "tel:&#43;18335550199") && strings.Contains(h, "1001 Texas Ave"), "no phone or address in the footer")
		check(strings.Contains(h, "/legal/privacy") && strings.Contains(h, "/account/security"), "no legal or preferences links")
		check(strings.Contains(h, "app-store.png") && strings.Contains(h, "google-play.png"), "no app store badges")
		// Light only in every client: a white body between the black header and footer, never a dark-grey variant.
		check(strings.Contains(h, `<meta name="color-scheme" content="light only">`) && strings.Contains(h, `<meta name="supported-color-schemes" content="light">`),
			"not declared light-only")
		check(!strings.Contains(h, "prefers-color-scheme") && !strings.Contains(h, "data-ogsc"), "a dark-mode variant")
		check(strings.Contains(h, `class="at-card at-pad" style="background-color:#FFFFFF;`), "the body is not white")
		check(strings.Contains(h, "max-width:600px") && strings.Contains(h, "@media only screen and (max-width: 620px)"), "not 600px / responsive")
		check(strings.Contains(h, "display:none;font-size:1px"), "no hidden preheader")
		check(!linkCSS.MatchString(h) && !scriptTag.MatchString(h), "external CSS or a script")
		check(!svgOrData.MatchString(h), "SVG or data: image")
		check(!hrefEmpty.MatchString(h), "empty link")
		check(!strings.Contains(h, "ZgotmplZ"), "an unsafe URL was filtered (ZgotmplZ)")
		check(len(h) < 100*1024, "over 100 KB (Gmail clips at ~102 KB)")
		for _, img := range imgTag.FindAllString(h, -1) {
			check(altAttr.MatchString(img), "image without alt text: "+img)
			if m := srcAttr.FindStringSubmatch(img); m != nil {
				check(domain.SafeURL(m[1]), "image not hosted: "+m[1])
			}
		}
		promotional := tpl.Category == domain.CategoryMarketing || s.Data["unsubscribeUrl"] != nil && s.Data["unsubscribeUrl"] != ""
		if promotional {
			check(strings.Contains(h, ">Unsubscribe</a>") && strings.Contains(h, "promotional email"), "promotional email without unsubscribe / CAN-SPAM copy")
		} else {
			check(strings.Contains(h, "Email preferences"), "no email preferences link")
		}
		if tpl.Category == domain.CategorySecurity {
			check(!adSectionRe.MatchString(h), "advertorial in a security email")
			check(strings.Contains(h, "security email"), "security footer wording")
		}
		if _, allowed := app.AdvertorialPolicy[tpl.Key]; allowed && tpl.Category != domain.CategorySecurity {
			check(adSectionRe.MatchString(h), "advertorial missing where the policy allows one")
			// Below the main content: after the last button or heading of the content.
			check(strings.Index(h, "Join our WhatsApp community") > adSectionRe.FindStringIndex(h)[0], "advertorial not above the footer")
		} else if allowed := app.AdvertorialPolicy[tpl.Key]; len(allowed) == 0 {
			check(!adSectionRe.MatchString(h), "advertorial in an email the policy does not allow")
		}
		check(strings.Contains(out.Text, "Help Center: https://afrotransact.com/help"), "text part without the footer")
		golden := filepath.Join(dir, tpl.Key+".html")
		if *updateGolden {
			if err := os.WriteFile(golden, []byte(h), 0o644); err != nil {
				t.Fatal(err)
			}
			continue
		}
		want, err := os.ReadFile(golden)
		if err != nil {
			t.Errorf("%s: no golden file (go test ./internal/app -run Branded -update): %v", tpl.Key, err)
			continue
		}
		if string(want) != h {
			t.Errorf("%s: render differs from %s (review, then -update)", tpl.Key, golden)
		}
	}
	if emails < 55 {
		t.Fatalf("only %d email templates", emails)
	}
}

// TestOrderConfirmationShowsPackagesTotalsAndAddress checks the order email's content blocks.
func TestOrderConfirmationShowsPackagesTotalsAndAddress(t *testing.T) {
	samples, _ := app.NewTemplateSamples(context.Background(), sampleLinks)
	for _, tpl := range seededTemplates(t) {
		if tpl.Key != "order.confirmed" || tpl.Channel != domain.ChannelEmail {
			continue
		}
		s, _ := samples.Sample(tpl.Key)
		out, err := brandedRenderer().Render(tpl, s.Data)
		if err != nil {
			t.Fatal(err)
		}
		for _, part := range []string{"Fulfilled by AfroTransact", "Sold and shipped by Mama Put Foods", "Yellow Garri, 1.5 kg",
			`alt="Yellow Garri, 1.5 kg"`, "Qty 2 · $6.50 each", "$13.00", "Subtotal", "$40.00", "Delivery", "$5.00", "Tax", "$3.00",
			"$48.00", "Ada Obi", "2207 Bagby St", "Houston, TX 77006", "Arrives by Wed, Oct 14", "AT-2026-7KQ3MD",
			"https://afrotransact.com/orders/AT-2026-7KQ3MD", "Help Center", "Complete your pantry", "v:roundrect"} {
			if !strings.Contains(out.HTML, part) {
				t.Errorf("order.confirmed lacks %q", part)
			}
		}
		if strings.Index(out.HTML, "Fulfilled by AfroTransact") > strings.Index(out.HTML, "Sold and shipped by") {
			t.Error("AfroTransact package must come first")
		}
		if strings.Index(out.HTML, "Complete your pantry") < strings.Index(out.HTML, "Delivering to") {
			t.Error("advertorial above the main content")
		}
	}
}

func TestSecurityEmailsNeverShowAdvertorials(t *testing.T) {
	tpl := domain.Template{Key: "identity.verification", Locale: "en", Channel: domain.ChannelEmail, Category: domain.CategorySecurity,
		Subject: "Code", BodyHTML: "<p>{{otp .code}}</p>", BodyText: "Code {{.code}}"}
	ads := []domain.Advertorial{{Kind: domain.AdCampaign, Title: "Party jollof, sorted.", CTAURL: "https://afrotransact.com/s"}}
	out, err := brandedRenderer().Render(tpl, map[string]any{"code": "123456", app.DataAdvertorials: ads})
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(out.HTML, "Party jollof") || !strings.Contains(out.HTML, "123456") {
		t.Fatal("security email rendered an advertorial")
	}
	if app.AdvertorialsAllowed("identity.verification", domain.CategorySecurity) || app.AdvertorialsAllowed("order.confirmed", domain.CategorySecurity) {
		t.Fatal("policy allows ads in security emails")
	}
}

func TestMarketingEmailsCarryUnsubscribeAndCanSpamCopy(t *testing.T) {
	tpl := domain.Template{Key: "marketing.weekly", Locale: "en", Channel: domain.ChannelEmail, Category: domain.CategoryMarketing,
		Subject: "This week", BodyHTML: `<h1>New this week</h1><p>Hi {{.firstName}}</p>{{button .url "Shop"}}`, BodyText: "Hi {{.firstName}}"}
	out, err := brandedRenderer().Render(tpl, map[string]any{"firstName": "Ada", "url": "https://afrotransact.com/s",
		app.DataUnsubscribeURL: "https://api.afrotransact.com/notifications/v1/unsubscribe?t=abc"})
	if err != nil {
		t.Fatal(err)
	}
	for _, part := range []string{`href="https://api.afrotransact.com/notifications/v1/unsubscribe?t=abc"`, "This is a promotional email",
		"1001 Texas Ave", ">Unsubscribe</a>"} {
		if !strings.Contains(out.HTML, part) {
			t.Errorf("marketing email lacks %q", part)
		}
	}
	if !strings.Contains(out.Text, "Unsubscribe: https://api.afrotransact.com/notifications/v1/unsubscribe?t=abc") {
		t.Errorf("text part lacks unsubscribe:\n%s", out.Text)
	}
}

func TestFullDocumentsAreSentAsIs(t *testing.T) {
	tpl := domain.Template{Key: "legacy", Locale: "en", Channel: domain.ChannelEmail, Category: domain.CategoryTransactional,
		Subject: "x", BodyHTML: "<!doctype html><html><body><p>{{.n}}</p></body></html>", BodyText: "{{.n}}"}
	out, err := brandedRenderer().Render(tpl, map[string]any{"n": "1"})
	if err != nil || out.HTML != "<!doctype html><html><body><p>1</p></body></html>" || out.Text != "1" {
		t.Fatalf("%+v %v", out, err)
	}
}

// maxSmsSegments is one GSM-7 segment for every text, two for delivery run alerts: their link carries the UTM
// attribution (source, medium, campaign, content) that makes every announcement visible in analytics.
func maxSmsSegments(key string) int {
	if strings.HasPrefix(key, "run.") {
		return 2
	}
	return 1
}

// TestSmsTemplatesAreShortAndCompliant: every SMS starts with the brand, non-security texts carry STOP, and each
// renders as one GSM-7 segment (two for run alerts) with its sample data.
func TestSmsTemplatesAreShortAndCompliant(t *testing.T) {
	samples, _ := app.NewTemplateSamples(context.Background(), sampleLinks)
	r := brandedRenderer()
	for _, tpl := range seededTemplates(t) {
		if tpl.Channel != domain.ChannelSMS {
			continue
		}
		s, _ := samples.Sample(tpl.Key)
		out, err := r.Render(tpl, s.Data)
		if err != nil {
			t.Errorf("%s: %v", tpl.Key, err)
			continue
		}
		if !strings.Contains(out.Text, "AfroTransact") {
			t.Errorf("%s: no brand: %q", tpl.Key, out.Text)
		}
		if tpl.Category != domain.CategorySecurity && !strings.Contains(out.Text, "STOP") {
			t.Errorf("%s: no STOP: %q", tpl.Key, out.Text)
		}
		if seg := domain.CountSmsSegments(out.Text); seg.Encoding != domain.EncodingGSM7 || seg.Segments > maxSmsSegments(tpl.Key) {
			t.Errorf("%s: %s, %d segments (%d units): %q", tpl.Key, seg.Encoding, seg.Segments, seg.Units, out.Text)
		}
	}
}
