package app_test

import (
	"fmt"
	"regexp"
	"sort"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

// parityLocales are the languages the platform sends in besides English; every delivery run and pre-order message
// (the keys below) must exist in each of them for every channel the English row has (S262).
var parityLocales = []string{"fr", "es", "ar", "yo"}

func parityKey(key string) bool {
	return strings.HasPrefix(key, "run.") || strings.HasPrefix(key, "preorder.")
}

var (
	actionRe  = regexp.MustCompile(`(?s)\{\{(.*?)\}\}`)
	literalRe = regexp.MustCompile("\"(?:[^\"\\\\]|\\\\.)*\"|`[^`]*`")
	tokenRe   = regexp.MustCompile(`[.$]?[A-Za-z_][A-Za-z0-9_.]*`)
	verbRe    = regexp.MustCompile(`%[sdvq]`)
)

// placeholderSignature lists every identifier, field and function used inside {{ }} actions of the template parts
// (string literals, the translatable words, are dropped), plus the printf verbs; two languages of one message must
// have the same signature, so a translation cannot lose or invent a placeholder, a condition or a block.
func placeholderSignature(parts ...string) string {
	var toks []string
	for _, p := range parts {
		for _, m := range actionRe.FindAllStringSubmatch(p, -1) {
			toks = append(toks, tokenRe.FindAllString(literalRe.ReplaceAllString(m[1], `""`), -1)...)
		}
		for _, v := range verbRe.FindAllString(p, -1) {
			toks = append(toks, "verb"+v)
		}
	}
	sort.Strings(toks)
	return strings.Join(toks, " ")
}

// TestRunAndPreorderTemplatesExistInEveryLocale fails when a delivery run or pre-order message has no row in
// fr, es, ar or yo for a channel the English row has, or when a translation differs from English in category,
// emptiness of subject / HTML / text, WhatsApp template name or placeholders.
func TestRunAndPreorderTemplatesExistInEveryLocale(t *testing.T) {
	all := map[string]domain.Template{}
	for _, tpl := range seededTemplates(t) {
		all[tpl.Key+"|"+tpl.Locale+"|"+string(tpl.Channel)] = tpl
	}
	english := 0
	for id, en := range all {
		if en.Locale != "en" || !parityKey(en.Key) {
			continue
		}
		english++
		for _, loc := range parityLocales {
			name := fmt.Sprintf("%s|%s|%s", en.Key, loc, en.Channel)
			tr, ok := all[name]
			if !ok {
				t.Errorf("%s has no %s row (%s)", id, loc, name)
				continue
			}
			if tr.Category != en.Category {
				t.Errorf("%s: category %s, English %s", name, tr.Category, en.Category)
			}
			if (tr.Subject == "") != (en.Subject == "") || (tr.BodyHTML == "") != (en.BodyHTML == "") || (tr.BodyText == "") != (en.BodyText == "") {
				t.Errorf("%s: subject / HTML / text present differently from English", name)
			}
			if en.Channel == domain.ChannelChat && (tr.Subject != en.Subject || tr.BodyText != en.BodyText) {
				t.Errorf("%s: a WhatsApp row keeps the approved template name and parameter order of English", name)
			}
			if got, want := placeholderSignature(tr.Subject, tr.BodyHTML, tr.BodyText), placeholderSignature(en.Subject, en.BodyHTML, en.BodyText); got != want {
				t.Errorf("%s: placeholders differ from English\n got: %s\nwant: %s", name, got, want)
			}
			if loc == "ar" && en.Channel == domain.ChannelEmail && !strings.Contains(tr.BodyHTML, `dir="rtl"`) {
				t.Errorf("%s: Arabic email without dir=\"rtl\"", name)
			}
		}
	}
	if english < 30 {
		t.Fatalf("only %d English run / pre-order templates were seeded", english)
	}
}

// TestLocaleParityCatchesAMissingLocale proves the signature check itself: dropping a placeholder changes it.
func TestLocaleParityCatchesAMissingLocale(t *testing.T) {
	a := placeholderSignature("Hi {{.firstName}}", `{{button .emailUrl "Go"}}`, "{{if .x}}{{.x}}{{end}}")
	b := placeholderSignature("Salut {{.firstName}}", `{{button .emailUrl "Aller"}}`, "{{if .x}}{{.x}}{{end}}")
	c := placeholderSignature("Salut", `{{button .emailUrl "Aller"}}`, "{{if .x}}{{.x}}{{end}}")
	if a != b || a == c {
		t.Fatalf("signature does not tell translated words from lost placeholders:\n%s\n%s\n%s", a, b, c)
	}
}
