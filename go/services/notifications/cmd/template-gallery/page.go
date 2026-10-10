package main

import (
	"bytes"
	_ "embed" // gallery.html.tmpl
	"encoding/base64"
	"encoding/json"
	"fmt"
	"html/template"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

//go:embed gallery.html.tmpl
var galleryTemplate string

// assets are the AfroTransact design tokens (web/packages/tokens/src/tokens.css) and the self-hosted Figtree faces,
// inlined so the gallery is one self-contained file.
type assets struct {
	tokensCSS string
	fontCSS   string
}

// fallbackTokens (CSS design tokens, not credentials) are used when the monorepo's tokens.css cannot be found (the page still renders, in system fonts).
//
//nolint:gosec // G101: CSS custom properties named tokens, not credentials
const fallbackTokens = `:root{--at-ink-0:#FFFFFF;--at-ink-25:#FCFBF8;--at-ink-50:#F6F4EF;--at-ink-100:#EEEBE4;--at-ink-200:#E0DCD2;
--at-ink-300:#C8C3B8;--at-ink-550:#69645B;--at-ink-600:#5D5951;--at-ink-700:#44413B;--at-ink-800:#2D2B27;--at-ink-900:#191816;
--at-ink-1000:#000000;--at-gold-50:#FFFBE6;--at-gold-500:#FFD400;--at-gold-800:#7A6400;--at-green-50:#E8F5F0;--at-green-200:#93D2BC;
--at-green-300:#57B597;--at-green-500:#0A8264;--at-green-700:#055E47;--at-green-900:#033226;--at-red-50:#FDEDEC;--at-red-600:#B42318;
--at-color-canvas:var(--at-ink-0);--at-color-surface:var(--at-ink-0);--at-color-surface-muted:var(--at-ink-50);
--at-color-text:var(--at-ink-900);--at-color-text-muted:var(--at-ink-600);--at-color-border:var(--at-ink-200);
--at-color-link:var(--at-green-700);--at-color-focus:var(--at-green-500);--at-color-danger:var(--at-red-600);
--at-color-danger-bg:var(--at-red-50);--at-font-sans:"Figtree",ui-sans-serif,system-ui,sans-serif;
--at-font-mono:ui-monospace,Menlo,Consolas,monospace;--at-radius-sm:6px;--at-radius-md:8px;--at-radius-lg:12px;--at-radius-pill:999px;
--at-shadow-sm:0 1px 2px rgb(25 24 22 / .08);--at-shadow-md:0 4px 14px rgb(25 24 22 / .12);--at-brand-whatsapp:#25D366}
html,body{overflow-x:hidden}*{scrollbar-width:none}*::-webkit-scrollbar{display:none}`

// findAssets locates the monorepo (repo, or upwards from the working directory) and reads tokens.css and the
// Figtree latin faces installed by pnpm. Missing pieces are logged and replaced by fallbacks.
func findAssets(repo string, log *slog.Logger) assets {
	const tokensRel = "web/packages/tokens/src/tokens.css"
	if repo == "" {
		dir, _ := os.Getwd()
		for dir != "" {
			if _, err := os.Stat(filepath.Join(dir, tokensRel)); err == nil {
				repo = dir
				break
			}
			parent := filepath.Dir(dir)
			if parent == dir {
				break
			}
			dir = parent
		}
	}
	out := assets{tokensCSS: fallbackTokens}
	if repo == "" {
		log.Warn("monorepo not found (pass --repo): using fallback tokens and system fonts")
		return out
	}
	if b, err := os.ReadFile(filepath.Join(repo, tokensRel)); err == nil { //nolint:gosec // G304: the monorepo's own tokens.css
		out.tokensCSS = string(b)
	} else {
		log.Warn("tokens.css not readable: using fallback tokens", "error", err)
	}
	var fonts strings.Builder
	for _, w := range []string{"400", "500", "600", "700"} {
		f := filepath.Join(repo, "web/packages/tokens/node_modules/@fontsource/figtree/files", "figtree-latin-"+w+"-normal.woff2")
		b, err := os.ReadFile(f) //nolint:gosec // G304: the monorepo's own font files
		if err != nil {
			log.Warn("Figtree not installed (run pnpm install): system fonts are used", "weight", w)
			continue
		}
		fmt.Fprintf(&fonts, "@font-face{font-family:\"Figtree\";font-style:normal;font-weight:%s;font-display:swap;src:url(data:font/woff2;base64,%s) format(\"woff2\")}\n",
			w, base64.StdEncoding.EncodeToString(b))
	}
	out.fontCSS = fonts.String()
	return out
}

// PageWriter writes the gallery page.
type PageWriter interface {
	Write(w io.Writer, entries []app.GalleryEntry, generated time.Time) error
}

type pageWriterImpl struct {
	tpl    *template.Template
	assets assets
}

var _ PageWriter = (*pageWriterImpl)(nil)

func newPageWriter(a assets) PageWriter {
	return &pageWriterImpl{tpl: template.Must(template.New("gallery").Parse(galleryTemplate)), assets: a}
}

var areaLabels = map[string]string{
	app.AreaAccount: "Account", app.AreaOrders: "Orders & shipments", app.AreaSeller: "Seller", app.AreaPayouts: "Payouts",
	app.AreaBookings: "Bookings", app.AreaTrust: "Trust & safety", app.AreaRetail: "Retail", app.AreaMarketing: "Marketing",
	app.AreaSecurity: "Security", app.AreaOther: "Other",
}

var channelLabels = map[domain.Channel]string{domain.ChannelEmail: "Email", domain.ChannelSMS: "SMS",
	domain.ChannelPush: "Push", domain.ChannelChat: "WhatsApp"}

type pageView struct {
	Generated               string
	TokensCSS, FontCSS      template.CSS
	Templates, Keys, Failed int
	Failures                []sectionView
	Areas                   []areaView
	Locales                 []string
	Channels                []channelView
}

type channelView struct{ Key, Label string }

type areaView struct {
	Key, Label string
	Cards      []*cardView
}

type cardView struct {
	ID, Key, Locale, Area, Search, Source, SampleJSON string
	Categories, Channels, EventTypes                  []string
	Sections                                          []sectionView
	Failed                                            bool
}

type sectionView struct {
	CardID, Key, Locale, Channel, ChannelLabel, Category, CreatedAt, CreatedBy string
	Version                                                                    int
	Subject, HTML, Text, Err                                                   string
	SMS                                                                        *domain.SmsSegments
	Chars                                                                      int
}

func (p *pageWriterImpl) Write(w io.Writer, entries []app.GalleryEntry, generated time.Time) error {
	v := pageView{Generated: generated.Format("Jan 2, 2006 15:04 MST"), Templates: len(entries),
		// The repo's own tokens.css and base64 font faces (never user input) go into <style> unescaped.
		TokensCSS: template.CSS(p.assets.tokensCSS), FontCSS: template.CSS(p.assets.fontCSS)} //nolint:gosec // G203: trusted repo files
	cards := map[string]*cardView{}
	areas := map[string]*areaView{}
	for _, e := range entries {
		t := e.Template
		id := "t-" + strings.NewReplacer(".", "-", "_", "-").Replace(t.Key) + "-" + t.Locale
		c, ok := cards[id]
		if !ok {
			data, err := json.MarshalIndent(e.Sample.Data, "", "  ")
			if err != nil {
				return fmt.Errorf("sample data of %s: %w", t.Key, err)
			}
			c = &cardView{ID: id, Key: t.Key, Locale: t.Locale, Area: e.Sample.Area, Source: string(e.Sample.Source),
				SampleJSON: string(data), EventTypes: e.Sample.EventTypes}
			cards[id] = c
			a, ok := areas[c.Area]
			if !ok {
				a = &areaView{Key: c.Area, Label: areaLabels[c.Area]}
				areas[c.Area] = a
			}
			a.Cards = append(a.Cards, c)
			if !slices.Contains(v.Locales, t.Locale) {
				v.Locales = append(v.Locales, t.Locale)
			}
		}
		s := sectionView{CardID: id, Key: t.Key, Locale: t.Locale, Channel: string(t.Channel), ChannelLabel: channelLabels[t.Channel],
			Category: string(t.Category), Version: t.Version, CreatedBy: t.CreatedBy, Subject: e.Rendered.Subject, HTML: e.Rendered.HTML,
			Text: e.Rendered.Text, Err: e.Err, SMS: e.SMS, Chars: utf8.RuneCountInString(e.Rendered.Text)}
		if !t.CreatedAt.IsZero() {
			s.CreatedAt = t.CreatedAt.UTC().Format("Jan 2, 2006")
		}
		if t.Channel == domain.ChannelChat || t.Channel == domain.ChannelPush {
			s.Subject = t.Subject // WhatsApp template name / push title as stored (rendered for push below)
			if t.Channel == domain.ChannelPush {
				s.Subject = e.Rendered.Subject
			}
		}
		c.Sections = append(c.Sections, s)
		c.Channels = append(c.Channels, s.ChannelLabel)
		if !slices.Contains(c.Categories, s.Category) {
			c.Categories = append(c.Categories, s.Category)
		}
		c.Search = strings.ToLower(strings.Join([]string{c.Search, t.Key, s.Subject, string(t.Category), c.Area, areaLabels[c.Area],
			strings.Join(c.EventTypes, " ")}, " "))
		if e.Err != "" {
			c.Failed = true
			v.Failures = append(v.Failures, s)
		}
	}
	v.Keys = len(cards)
	for _, a := range app.Areas {
		if av, ok := areas[a]; ok {
			v.Areas = append(v.Areas, *av)
		}
	}
	for _, ch := range []domain.Channel{domain.ChannelEmail, domain.ChannelSMS, domain.ChannelPush, domain.ChannelChat} {
		v.Channels = append(v.Channels, channelView{Key: string(ch), Label: channelLabels[ch]})
	}
	v.Failed = len(v.Failures)
	slices.Sort(v.Locales)
	var b bytes.Buffer
	if err := p.tpl.Execute(&b, v); err != nil {
		return fmt.Errorf("execute gallery template: %w", err)
	}
	if _, err := w.Write(b.Bytes()); err != nil {
		return fmt.Errorf("write: %w", err)
	}
	return nil
}
