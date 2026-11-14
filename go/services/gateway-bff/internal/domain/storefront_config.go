package domain

import (
	"fmt"
	"math"
	"net"
	"net/url"
	"regexp"
	"strings"
	"unicode"
	"unicode/utf8"
)

// StoreConfigVersion is the schema version of StoreConfig.
const StoreConfigVersion = 1

// Theme presets a seller can choose (the look itself, palette and type, lives in @afrotransact/tokens).
const (
	PresetClean      = "clean"
	PresetWarmMarket = "warm-market"
	PresetBold       = "bold"
	PresetMidnight   = "midnight"
	PresetHeritage   = "heritage"
	PresetFresh      = "fresh"
)

// presetCanvas is the page background of each preset (the colour an accent is read against). It mirrors the palettes in
// web/packages/tokens/src/storefront-theme.ts; a test in the tokens package fails when the two drift apart.
var presetCanvas = map[string]string{
	PresetClean:      "#ffffff",
	PresetWarmMarket: "#fffaf2",
	PresetBold:       "#ffffff",
	PresetMidnight:   "#0f1115",
	PresetHeritage:   "#fbf7ef",
	PresetFresh:      "#f6faf7",
}

// Sections a seller can order and switch (the header, hero and All products are always there).
var storeSectionIDs = []string{"announcement", "featured", "categories", "about", "policies", "reviews"}

// Social networks a link can point to, and the hosts each may use (www. and m. prefixes are ignored).
var socialHosts = map[string][]string{
	"instagram": {"instagram.com"},
	"facebook":  {"facebook.com", "fb.com", "fb.me"},
	"tiktok":    {"tiktok.com"},
	"x":         {"x.com", "twitter.com"},
	"youtube":   {"youtube.com", "youtu.be"},
	"whatsapp":  {"wa.me", "whatsapp.com", "api.whatsapp.com"},
	"website":   nil,
}

// StoreConfig is how a seller's storefront looks and which sections it has. Plain text only; images from AfroTransact
// media only; links https only (contract: components.schemas.StoreConfig).
type StoreConfig struct {
	SchemaVersion int                `json:"schemaVersion"`
	Theme         StoreTheme         `json:"theme"`
	Hero          StoreHero          `json:"hero"`
	Announcement  StoreAnnouncement  `json:"announcement"`
	Grid          StoreGrid          `json:"grid"`
	Sections      []StoreSection     `json:"sections"`
	Featured      []string           `json:"featured"`
	Categories    StoreCategoryPick  `json:"categories"`
	About         StoreAbout         `json:"about"`
	Policies      StorePolicies      `json:"policies"`
	Contact       StoreContact       `json:"contact"`
	Pickup        StorePickupSetting `json:"pickup"`
	Social        []StoreSocialLink  `json:"social"`
}

// StoreTheme is the preset and an optional accent colour.
type StoreTheme struct {
	Preset string  `json:"preset"`
	Accent *string `json:"accent"`
}

// StoreHero is the top of the page.
type StoreHero struct {
	Style     string  `json:"style"`
	Tagline   string  `json:"tagline"`
	BannerURL *string `json:"bannerUrl"`
	FocalX    int     `json:"focalX"`
	FocalY    int     `json:"focalY"`
}

// StoreAnnouncement is the optional bar above the header.
type StoreAnnouncement struct {
	Text string  `json:"text"`
	Href *string `json:"href"`
}

// StoreGrid is the All products grid density.
type StoreGrid struct {
	Columns int `json:"columns"`
}

// StoreSection is one optional section's place and visibility.
type StoreSection struct {
	ID      string `json:"id"`
	Enabled bool   `json:"enabled"`
}

// StoreCategoryPick is how the categories row is made: from the live products, or the seller's own choice.
type StoreCategoryPick struct {
	Mode   string   `json:"mode"`
	Chosen []string `json:"chosen"`
}

// StoreAbout is the story section.
type StoreAbout struct {
	Heading  string  `json:"heading"`
	Body     string  `json:"body"`
	PhotoURL *string `json:"photoUrl"`
}

// StorePolicies are the seller's own words about shipping and returns.
type StorePolicies struct {
	Shipping string `json:"shipping"`
	Returns  string `json:"returns"`
}

// StoreContact is what the seller chooses to show.
type StoreContact struct {
	ShowEmail bool   `json:"showEmail"`
	Phone     string `json:"phone"`
	Hours     string `json:"hours"`
}

// StorePickupSetting is the seller's switch to show pickup on the page.
type StorePickupSetting struct {
	Show bool `json:"show"`
}

// StoreSocialLink is one social profile link.
type StoreSocialLink struct {
	Network string `json:"network"`
	URL     string `json:"url"`
}

// StorePolicy is what a config is checked against: where images may come from.
type StorePolicy struct {
	// MediaPrefixes are the URL prefixes of AfroTransact media ("https://media.example.com/", "http://localhost:9000/media/").
	MediaPrefixes []string
}

// DefaultStoreConfig is the starting point: the Clean preset, every optional section on, nothing invented.
func DefaultStoreConfig() StoreConfig {
	secs := make([]StoreSection, 0, len(storeSectionIDs))
	for _, id := range storeSectionIDs {
		secs = append(secs, StoreSection{ID: id, Enabled: id != "announcement"})
	}
	return StoreConfig{
		SchemaVersion: StoreConfigVersion,
		Theme:         StoreTheme{Preset: PresetClean},
		Hero:          StoreHero{Style: "image", FocalX: 50, FocalY: 50},
		Grid:          StoreGrid{Columns: 4},
		Sections:      secs,
		Featured:      []string{},
		Categories:    StoreCategoryPick{Mode: "auto", Chosen: []string{}},
		Social:        []StoreSocialLink{},
	}
}

var (
	htmlish   = regexp.MustCompile(`<[a-zA-Z/!?]`)
	hexColour = regexp.MustCompile(`^#[0-9a-f]{6}$`)
	phoneRe   = regexp.MustCompile(`^[+0-9 ()\-.]{6,24}$`)
	atinRe    = regexp.MustCompile(`^[A-Za-z0-9_-]{1,40}$`)
	manyLines = regexp.MustCompile(`\n{3,}`)
)

// PlainText cleans free text for the page: line breaks are kept, other control characters are dropped, runs of blank lines
// collapse, and text that looks like markup is refused (it would only ever be shown as text, but a seller who pastes HTML
// should be told). ok is false when the text is too long or looks like markup.
func PlainText(s string, maxRunes int) (string, bool) {
	s = strings.ReplaceAll(strings.ReplaceAll(s, "\r\n", "\n"), "\r", "\n")
	s = strings.Map(func(r rune) rune {
		if r == '\t' {
			return ' '
		}
		if r == '\n' {
			return r
		}
		if unicode.IsControl(r) || r == 0x2028 || r == 0x2029 || (r >= 0x200b && r <= 0x200f) || r == 0xfeff {
			return -1
		}
		return r
	}, s)
	s = strings.TrimSpace(manyLines.ReplaceAllString(s, "\n\n"))
	if utf8.RuneCountInString(s) > maxRunes || htmlish.MatchString(s) {
		return s, false
	}
	return s, true
}

// SingleLine is PlainText with no line breaks.
func SingleLine(s string, maxRunes int) (string, bool) {
	out, ok := PlainText(strings.NewReplacer("\n", " ").Replace(s), maxRunes)
	return out, ok
}

// MediaURLOK reports whether u is an image address on AfroTransact media: absolute, no credentials, no query tricks, and
// under one of the allowed prefixes.
func MediaURLOK(u string, p StorePolicy) bool {
	if len(u) == 0 || len(u) > 2048 {
		return false
	}
	parsed, err := url.Parse(u)
	if err != nil || parsed.User != nil || parsed.Host == "" || (parsed.Scheme != "https" && parsed.Scheme != "http") {
		return false
	}
	if strings.Contains(parsed.Path, "..") || strings.ContainsAny(u, " \t\r\n\\") {
		return false
	}
	for _, pre := range p.MediaPrefixes {
		if pre != "" && strings.HasPrefix(u, pre) {
			return true
		}
	}
	return false
}

func publicHost(h string) bool {
	h = strings.ToLower(h)
	if h == "" || h == "localhost" || strings.HasSuffix(h, ".local") || strings.HasSuffix(h, ".internal") || !strings.Contains(h, ".") {
		return false
	}
	return net.ParseIP(h) == nil
}

func bareHost(h string) string {
	h = strings.ToLower(h)
	for _, p := range []string{"www.", "m."} {
		h = strings.TrimPrefix(h, p)
	}
	return h
}

// SafeLink returns u as a clean https address (host with a dot, no credentials, no IP, no script scheme), or false.
func SafeLink(u string) (string, bool) {
	parsed, err := url.Parse(strings.TrimSpace(u))
	if err != nil || parsed.Scheme != "https" || parsed.User != nil || !publicHost(parsed.Hostname()) || parsed.Port() != "" && parsed.Port() != "443" {
		return "", false
	}
	parsed.Fragment = ""
	out := parsed.String()
	return out, len(out) <= 2048
}

// SocialLink returns the link when it is https on a host that belongs to the network ("website" accepts any public host).
func SocialLink(network, raw string) (string, bool) {
	hosts, known := socialHosts[network]
	if !known {
		return "", false
	}
	clean, ok := SafeLink(raw)
	if !ok {
		return "", false
	}
	if hosts == nil {
		return clean, true
	}
	parsed, _ := url.Parse(clean)
	h := bareHost(parsed.Hostname())
	for _, allowed := range hosts {
		if h == allowed || strings.HasSuffix(h, "."+allowed) {
			return clean, true
		}
	}
	return "", false
}

// luminance is the WCAG relative luminance of #rrggbb.
func luminance(hex string) float64 {
	var r, g, b int
	if _, err := fmt.Sscanf(hex, "#%02x%02x%02x", &r, &g, &b); err != nil {
		return 0
	}
	lin := func(v int) float64 {
		c := float64(v) / 255
		if c <= 0.03928 {
			return c / 12.92
		}
		return math.Pow((c+0.055)/1.055, 2.4)
	}
	return 0.2126*lin(r) + 0.7152*lin(g) + 0.0722*lin(b)
}

// Contrast is the WCAG contrast ratio of two #rrggbb colours (1 to 21).
func Contrast(a, b string) float64 {
	la, lb := luminance(a), luminance(b)
	if la < lb {
		la, lb = lb, la
	}
	return (la + 0.05) / (lb + 0.05)
}

// AccentOK reports whether an accent can be used on the preset: it must read as text at WCAG AA (4.5:1) on the preset's
// canvas. The text on top of it (buttons) is computed by the page, so that side can never fail.
func AccentOK(preset, accent string) bool {
	canvas, ok := presetCanvas[preset]
	return ok && hexColour.MatchString(accent) && Contrast(accent, canvas) >= 4.5
}

// ValidateStoreConfig checks and cleans a config a seller sent. The returned map is empty when it is valid; keys are
// config paths ("hero.tagline"). The returned config is normalised (trimmed, colours lower-cased, sections complete).
func ValidateStoreConfig(in StoreConfig, p StorePolicy) (StoreConfig, map[string]string) {
	errs := map[string]string{}
	out := DefaultStoreConfig()
	text := func(path, v string, max int, line bool) string {
		var s string
		var ok bool
		if line {
			s, ok = SingleLine(v, max)
		} else {
			s, ok = PlainText(v, max)
		}
		if !ok {
			if utf8.RuneCountInString(s) > max {
				errs[path] = fmt.Sprintf("At most %d characters", max)
			} else {
				errs[path] = "Plain text only: remove the HTML"
			}
		}
		return s
	}
	image := func(path string, v *string) *string {
		if v == nil || strings.TrimSpace(*v) == "" {
			return nil
		}
		u := strings.TrimSpace(*v)
		if !MediaURLOK(u, p) {
			errs[path] = "Upload the image again"
			return nil
		}
		return &u
	}
	if in.SchemaVersion != StoreConfigVersion {
		errs["schemaVersion"] = "Unsupported version"
	}
	switch in.Theme.Preset {
	case PresetClean, PresetWarmMarket, PresetBold, PresetMidnight, PresetHeritage, PresetFresh:
		out.Theme.Preset = in.Theme.Preset
	default:
		errs["theme.preset"] = "Choose one of the looks"
	}
	if in.Theme.Accent != nil && strings.TrimSpace(*in.Theme.Accent) != "" {
		a := strings.ToLower(strings.TrimSpace(*in.Theme.Accent))
		switch {
		case !hexColour.MatchString(a):
			errs["theme.accent"] = "Use a colour like #1a7f4b"
		case errs["theme.preset"] == "" && !AccentOK(out.Theme.Preset, a):
			errs["theme.accent"] = "That colour is too light to read on this look. Choose a darker one"
		default:
			out.Theme.Accent = &a
		}
	}
	switch in.Hero.Style {
	case "image", "colour", "split":
		out.Hero.Style = in.Hero.Style
	default:
		errs["hero.style"] = "Choose image, colour or split"
	}
	out.Hero.Tagline = text("hero.tagline", in.Hero.Tagline, 140, true)
	out.Hero.BannerURL = image("hero.bannerUrl", in.Hero.BannerURL)
	if in.Hero.FocalX < 0 || in.Hero.FocalX > 100 || in.Hero.FocalY < 0 || in.Hero.FocalY > 100 {
		errs["hero.focalX"] = "Between 0 and 100"
	} else {
		out.Hero.FocalX, out.Hero.FocalY = in.Hero.FocalX, in.Hero.FocalY
	}
	out.Announcement.Text = text("announcement.text", in.Announcement.Text, 140, true)
	if in.Announcement.Href != nil && strings.TrimSpace(*in.Announcement.Href) != "" {
		h := strings.TrimSpace(*in.Announcement.Href)
		if strings.HasPrefix(h, "/") && !strings.HasPrefix(h, "//") && !strings.ContainsAny(h, "\\ \t\r\n") && len(h) <= 512 {
			out.Announcement.Href = &h
		} else if clean, ok := SafeLink(h); ok {
			out.Announcement.Href = &clean
		} else {
			errs["announcement.href"] = "Use a page on your store or a full https link"
		}
	}
	if in.Grid.Columns == 3 || in.Grid.Columns == 4 {
		out.Grid.Columns = in.Grid.Columns
	} else {
		errs["grid.columns"] = "Choose 3 or 4"
	}
	seen := map[string]bool{}
	ordered := make([]StoreSection, 0, len(storeSectionIDs))
	for _, s := range in.Sections {
		if !contains(storeSectionIDs, s.ID) || seen[s.ID] {
			errs["sections"] = "Each section can appear once"
			continue
		}
		seen[s.ID] = true
		ordered = append(ordered, s)
	}
	for _, id := range storeSectionIDs {
		if !seen[id] {
			ordered = append(ordered, StoreSection{ID: id})
		}
	}
	out.Sections = ordered
	if len(in.Featured) > 8 {
		errs["featured"] = "Feature at most 8 products"
	}
	out.Featured = out.Featured[:0]
	dup := map[string]bool{}
	for _, a := range in.Featured {
		a = strings.TrimSpace(a)
		if !atinRe.MatchString(a) {
			errs["featured"] = "Pick products from the list"
			continue
		}
		if !dup[a] && len(out.Featured) < 8 {
			dup[a] = true
			out.Featured = append(out.Featured, a)
		}
	}
	switch in.Categories.Mode {
	case "auto", "chosen":
		out.Categories.Mode = in.Categories.Mode
	default:
		errs["categories.mode"] = "Choose automatic or your own"
	}
	if len(in.Categories.Chosen) > 8 {
		errs["categories.chosen"] = "Choose at most 8 categories"
	}
	for _, c := range in.Categories.Chosen {
		c, ok := SingleLine(c, 80)
		if !ok || c == "" {
			errs["categories.chosen"] = "Pick categories from the list"
			continue
		}
		if len(out.Categories.Chosen) < 8 {
			out.Categories.Chosen = append(out.Categories.Chosen, c)
		}
	}
	out.About.Heading = text("about.heading", in.About.Heading, 80, true)
	out.About.Body = text("about.body", in.About.Body, 2000, false)
	out.About.PhotoURL = image("about.photoUrl", in.About.PhotoURL)
	out.Policies.Shipping = text("policies.shipping", in.Policies.Shipping, 600, false)
	out.Policies.Returns = text("policies.returns", in.Policies.Returns, 600, false)
	out.Contact.ShowEmail = in.Contact.ShowEmail
	if ph := strings.TrimSpace(in.Contact.Phone); ph != "" {
		if phoneRe.MatchString(ph) {
			out.Contact.Phone = ph
		} else {
			errs["contact.phone"] = "Use digits, spaces, + ( ) and -"
		}
	}
	out.Contact.Hours = text("contact.hours", in.Contact.Hours, 200, true)
	out.Pickup.Show = in.Pickup.Show
	if len(in.Social) > 6 {
		errs["social"] = "At most 6 links"
	}
	for i, l := range in.Social {
		if i >= 6 {
			break
		}
		clean, ok := SocialLink(l.Network, l.URL)
		if !ok {
			errs[fmt.Sprintf("social.%d.url", i)] = "Use the full https link of your profile"
			continue
		}
		out.Social = append(out.Social, StoreSocialLink{Network: l.Network, URL: clean})
	}
	return out, errs
}

// SanitizeStoreConfig is the defensive pass on a stored config before it goes to the public: whatever fails validation is
// replaced by its default, never an error (a bad value must not take a live page down, and never reaches a browser).
func SanitizeStoreConfig(in StoreConfig, p StorePolicy) StoreConfig {
	out, errs := ValidateStoreConfig(in, p)
	if len(errs) == 0 {
		return out
	}
	def := DefaultStoreConfig()
	// Re-run field by field: copy each part that passed, default the rest.
	res := def
	if errs["theme.preset"] == "" {
		res.Theme.Preset = out.Theme.Preset
		if errs["theme.accent"] == "" {
			res.Theme.Accent = out.Theme.Accent
		}
	}
	if errs["hero.style"] == "" {
		res.Hero.Style = out.Hero.Style
	}
	if errs["hero.tagline"] == "" {
		res.Hero.Tagline = out.Hero.Tagline
	}
	if errs["hero.bannerUrl"] == "" {
		res.Hero.BannerURL = out.Hero.BannerURL
	}
	if errs["hero.focalX"] == "" {
		res.Hero.FocalX, res.Hero.FocalY = out.Hero.FocalX, out.Hero.FocalY
	}
	if errs["announcement.text"] == "" {
		res.Announcement.Text = out.Announcement.Text
	}
	if errs["announcement.href"] == "" {
		res.Announcement.Href = out.Announcement.Href
	}
	if errs["grid.columns"] == "" {
		res.Grid = out.Grid
	}
	if errs["sections"] == "" {
		res.Sections = out.Sections
	}
	if errs["featured"] == "" {
		res.Featured = out.Featured
	}
	if errs["categories.mode"] == "" && errs["categories.chosen"] == "" {
		res.Categories = out.Categories
	}
	if errs["about.heading"] == "" {
		res.About.Heading = out.About.Heading
	}
	if errs["about.body"] == "" {
		res.About.Body = out.About.Body
	}
	if errs["about.photoUrl"] == "" {
		res.About.PhotoURL = out.About.PhotoURL
	}
	if errs["policies.shipping"] == "" {
		res.Policies.Shipping = out.Policies.Shipping
	}
	if errs["policies.returns"] == "" {
		res.Policies.Returns = out.Policies.Returns
	}
	res.Contact.ShowEmail = out.Contact.ShowEmail
	if errs["contact.phone"] == "" {
		res.Contact.Phone = out.Contact.Phone
	}
	if errs["contact.hours"] == "" {
		res.Contact.Hours = out.Contact.Hours
	}
	res.Pickup = out.Pickup
	res.Social = out.Social
	return res
}

func contains(list []string, v string) bool {
	for _, x := range list {
		if x == v {
			return true
		}
	}
	return false
}
