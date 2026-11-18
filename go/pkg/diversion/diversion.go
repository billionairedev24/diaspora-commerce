// Package diversion is the one detector for text that steers a customer away from AfroTransact: web addresses, email
// addresses, phone numbers (written in digits, in words or with look-alike characters), contact handles and phrases such as
// "WhatsApp" or "order direct".
//
// The rules are specified by the golden vectors in contracts/diversion/vectors.json. The Java detector in
// java/libs/module-core (com.afrotransact.core.diversion) implements the same rules and runs the same file, so the two cannot
// drift. Change a rule here, change the vectors, change the Java class.
//
// It reduces casual diversion. It cannot stop a determined seller (see docs/engineering/off-platform-diversion.md).
package diversion

import (
	"regexp"
	"strings"
	"unicode"
)

// Kind is what a finding looks like.
type Kind string

// The kinds of finding.
const (
	KindURL           Kind = "url"
	KindEmail         Kind = "email"
	KindPhone         Kind = "phone"
	KindSpelledDigits Kind = "spelled_digits"
	KindHandle        Kind = "handle"
	KindPhrase        Kind = "phrase"
)

// Finding is one thing the scan found. Excerpt is the matched text (for staff and the seller; never log it).
type Finding struct {
	Kind    Kind   `json:"kind"`
	Excerpt string `json:"excerpt"`
}

// Options tune the scan.
type Options struct {
	// Strict is for stores that were flagged: it adds the looser rules (bare "DM", "contact us", seven-digit runs, two
	// spelled digits in a row).
	Strict bool
}

// Detector scans free text.
type Detector interface {
	// Scan returns what in text steers a customer away from AfroTransact; empty when the text is clean.
	Scan(text string, o Options) []Finding
}

// Default is the detector the services use.
func Default() Detector { return &detectorImpl{} }

type detectorImpl struct{}

var _ Detector = (*detectorImpl)(nil)

var (
	reURLScheme = regexp.MustCompile(`(?:https?|ftp)://|(?:^|[^a-z0-9])www\s?\.`)
	reDomain    = regexp.MustCompile(`(?:^|[^a-z0-9])[a-z0-9][a-z0-9-]*(?:\.[a-z0-9-]+)*\.(?:com|net|org|info|biz|shop|store|online|site|xyz|app|link|uk|ng|gh|ke|za|fr)(?:$|[^a-z0-9])`)
	reShort     = regexp.MustCompile(`(?:^|[^a-z0-9])(?:bit\.ly|wa\.me|t\.me|linktr\.ee|tinyurl\.com|goo\.gl|ig\.me|fb\.me|m\.me|instagr\.am|youtu\.be)(?:$|[^a-z0-9])`)
	// reAmbiguousTLD: endings that are also what a sentence run together by a missing space looks like ("it.Me"), so they count
	// only in lower case after a word of three or more characters ("myshop.co"); the check runs on the text before lower-casing.
	reAmbiguousTLD = regexp.MustCompile(`(?:^|[^A-Za-z0-9])[a-z0-9][a-z0-9-]{2,}\.(?:co|io|me|us|ca|ly|ee)(?:$|[^A-Za-z0-9])`)
	reBareTLD      = regexp.MustCompile(`(?:^|\s)\.(?:com|net|org|info|biz|shop|store|online|site|xyz|app|link)(?:$|[^a-z0-9])`)
	reEmail        = regexp.MustCompile(`[a-z0-9][a-z0-9._%+-]*@[a-z0-9][a-z0-9-]*(?:\.[a-z0-9-]+)+`)
	reMailHost     = regexp.MustCompile(`\b(?:at|@)\s*(?:gmail|googlemail|yahoo|hotmail|outlook|icloud|protonmail|proton|aol|live|msn)\b|\b(?:gmail|hotmail|protonmail)\s*(?:dot|\.)\s*com\b`)
	reHandle       = regexp.MustCompile(`(?:^|[^a-z0-9_@])@[a-z0-9_][a-z0-9_.]{1,}`)

	reDotWord  = regexp.MustCompile(`\s*[\[({]\s*(?:dot|d0t)\s*[\])}]\s*|(?:^|\s+)(?:dot|d0t)\s+(?:com|c0m|net|org|info|biz|shop|store|online|site|xyz|app|link)\b`)
	reAtWord   = regexp.MustCompile(`\s*[\[({]\s*at\s*[\])}]\s*`)
	reOhDigit  = regexp.MustCompile(`(\d)[o](\d)`)
	reSpaceRun = regexp.MustCompile(`[ \t]+`)

	// Phone numbers: written in digits.
	rePhone = []*regexp.Regexp{
		regexp.MustCompile(`\d{10,}`),
		regexp.MustCompile(`\+\s?\d[\d ()\-.]{6,}\d`),
		regexp.MustCompile(`\(\d{3}\)\s?\d{3}[ .-]?\d{4}`),
		regexp.MustCompile(`(?:^|[^\d])\d{3}[ .-]\d{3}[ .-]\d{4}(?:$|[^\d])`),
		regexp.MustCompile(`(?:^|[^\d])\d{4}[ .-]\d{3}[ .-]\d{4}(?:$|[^\d])`),
		regexp.MustCompile(`(?:^|[^\d])\d{3}[ .-]\d{3}[ .-]\d{3}(?:$|[^\d])`),
		regexp.MustCompile(`(?:^|[^\d])\d{3}[ .-]\d{4}[ .-]\d{4}(?:$|[^\d])`),
		regexp.MustCompile(`(?:^|[^\d])0\d(?:[ .-]\d{2}){4}(?:$|[^\d])`),
		regexp.MustCompile(`(?:^|[^\d])\d{3}[-.]\d{4}(?:$|[^\d.-])`),
	}
	rePhoneStrict = regexp.MustCompile(`\d{7,}`)

	// Phrases that offer or ask for contact outside the site.
	rePhrases = regexp.MustCompile(`\b(?:` + strings.Join([]string{
		`what'?s\s*app`, `whatsapp`, `watsapp`, `whatsap`, `telegram`, `viber`, `wechat`, `we\s+chat`, `snapchat`, `signal\s+app`,
		`zelle`, `venmo`, `cash\s*app`, `cashapp`,
		`(?:dm|pm|inbox)\s+(?:me|us)\b`, `(?:call|text|sms|ring|phone|email|e-mail|whatsapp)\s+(?:me|us)\b`,
		`(?:message|msg|write\s+to)\s+(?:me|us)\s+(?:on|at)\b`,
		`(?:order|buy|purchase|pay)\s+(?:me\s+)?direct(?:ly)?`, `direct\s+(?:order|sale|deal|payment)s?`,
		`(?:cheaper|less|lower|better\s+price)\s+(?:if\s+you\s+)?(?:off|outside|away\s+from|without)\s+(?:the\s+)?(?:site|platform|app|afrotransact|website)`,
		`(?:off|outside)\s+(?:the\s+)?(?:site|platform|afrotransact)`,
		`(?:skip|avoid)\s+(?:the\s+)?(?:platform\s+|site\s+|afrotransact\s+)?fees`,
		`contact\s+me\s+(?:direct|directly|privately|outside)`,
	}, `|`) + `)`)
	rePhrasesStrict = regexp.MustCompile(`\b(?:dm|inbox)\b|\bcontact\s+(?:us|me)\b|\breach\s+(?:out|us|me)\b|\b(?:insta|ig|snap|fb)\s*[:=]`)

	reTokenSplit = regexp.MustCompile(`[\s,.\-/;:]+`)
)

var digitWords = map[string]struct{}{
	"zero": {}, "one": {}, "two": {}, "three": {}, "four": {}, "five": {}, "six": {}, "seven": {}, "eight": {}, "nine": {},
}

// normalise lowercases the text, folds look-alike digits to ASCII and zero-width characters away, and unwraps the usual
// "dot" and "at" disguises.
func normalise(s string) string {
	var b strings.Builder
	for _, r := range s {
		switch {
		case r == 0x200b || r == 0x200c || r == 0x200d || r == 0xfeff || r == 0x00ad:
			continue
		case r >= 0x0660 && r <= 0x0669:
			b.WriteRune('0' + (r - 0x0660))
		case r >= 0x06f0 && r <= 0x06f9:
			b.WriteRune('0' + (r - 0x06f0))
		case r >= 0xff10 && r <= 0xff19:
			b.WriteRune('0' + (r - 0xff10))
		case r == 0xff20:
			b.WriteRune('@')
		case r == 0x3002 || r == 0xff0e:
			b.WriteRune('.')
		case r == '’' || r == '‘':
			b.WriteRune('\'')
		case unicode.IsSpace(r):
			b.WriteRune(' ')
		default:
			b.WriteRune(unicode.ToLower(r))
		}
	}
	out := b.String()
	out = reDotWord.ReplaceAllStringFunc(out, func(m string) string {
		t := strings.TrimSpace(m)
		if strings.ContainsAny(t, "[({") {
			return "."
		}
		f := strings.Fields(t)
		return "." + strings.ReplaceAll(f[len(f)-1], "c0m", "com")
	})
	out = reAtWord.ReplaceAllString(out, "@")
	out = reOhDigit.ReplaceAllString(out, "${1}0${2}")
	out = reOhDigit.ReplaceAllString(out, "${1}0${2}")
	return reSpaceRun.ReplaceAllString(out, " ")
}

func (*detectorImpl) Scan(text string, o Options) []Finding {
	if strings.TrimSpace(text) == "" {
		return nil
	}
	t := normalise(text)
	raw := foldKeepCase(text)
	var out []Finding
	add := func(k Kind, re *regexp.Regexp) {
		if m := re.FindString(t); m != "" {
			out = append(out, Finding{Kind: k, Excerpt: strings.TrimSpace(strings.Trim(m, " ,;:!?()\"'"))})
		}
	}
	add(KindEmail, reEmail)
	add(KindEmail, reMailHost)
	add(KindURL, reURLScheme)
	add(KindURL, reDomain)
	add(KindURL, reShort)
	add(KindURL, reBareTLD)
	if m := reAmbiguousTLD.FindString(raw); m != "" {
		out = append(out, Finding{Kind: KindURL, Excerpt: strings.TrimSpace(strings.Trim(m, " ,;:!?()\"'"))})
	}
	add(KindHandle, reHandle)
	for _, re := range rePhone {
		add(KindPhone, re)
	}
	if o.Strict {
		add(KindPhone, rePhoneStrict)
	}
	if ex := spelledRun(t, o.Strict); ex != "" {
		out = append(out, Finding{Kind: KindSpelledDigits, Excerpt: ex})
	}
	add(KindPhrase, rePhrases)
	if o.Strict {
		add(KindPhrase, rePhrasesStrict)
	}
	return dedupe(out)
}

// spelledRun finds a run of single digits written as words (or single numerals between them): three words in a row, or
// seven single digits in a row. Strict: two words in a row.
func spelledRun(t string, strict bool) string {
	need := 3
	if strict {
		need = 2
	}
	toks := reTokenSplit.Split(t, -1)
	words, n, start := 0, 0, 0
	flush := func(end int) string {
		if words >= need || n >= 7 {
			return strings.Join(toks[start:end], " ")
		}
		return ""
	}
	for i, tok := range toks {
		_, isWord := digitWords[tok]
		isNum := len(tok) == 1 && tok[0] >= '0' && tok[0] <= '9'
		if isWord || isNum {
			if n == 0 {
				start = i
			}
			n++
			if isWord {
				words++
			}
			continue
		}
		if ex := flush(i); ex != "" {
			return ex
		}
		words, n = 0, 0
	}
	return flush(len(toks))
}

func dedupe(in []Finding) []Finding {
	seen := map[Kind]bool{}
	out := in[:0]
	for _, f := range in {
		if !seen[f.Kind] {
			seen[f.Kind] = true
			out = append(out, f)
		}
	}
	return out
}

// foldKeepCase removes zero-width characters and folds full-width dots and spaces, keeping the case.
func foldKeepCase(s string) string {
	var b strings.Builder
	for _, r := range s {
		switch {
		case r == 0x200b || r == 0x200c || r == 0x200d || r == 0xfeff || r == 0x00ad:
			continue
		case r == 0x3002 || r == 0xff0e:
			b.WriteRune('.')
		case unicode.IsSpace(r):
			b.WriteRune(' ')
		default:
			b.WriteRune(r)
		}
	}
	return b.String()
}

// PolicySentence is the sentence the terms and the warning beside the fields carry.
const PolicySentence = "Off-platform sales and steering buyers away from AfroTransact are not allowed."

var kindNouns = map[Kind]string{
	KindURL:           "web address",
	KindEmail:         "email address",
	KindPhone:         "phone number",
	KindSpelledDigits: "phone number spelled out in words",
	KindHandle:        "social handle",
	KindPhrase:        "wording that points customers to another way of buying",
}

// Message is what to tell the seller: what to remove and why. Plain and polite, no accusation.
func Message(fs []Finding) string {
	if len(fs) == 0 {
		return ""
	}
	nouns := make([]string, 0, len(fs))
	for _, f := range fs {
		nouns = append(nouns, kindNouns[f.Kind])
	}
	list := nouns[0]
	switch len(nouns) {
	case 1:
	case 2:
		list = nouns[0] + " and " + nouns[1]
	default:
		list = strings.Join(nouns[:len(nouns)-1], ", ") + " and " + nouns[len(nouns)-1]
	}
	return "Please remove the " + list + " (" + quote(fs[0].Excerpt) + "). Customers reach you through AfroTransact messages, and keeping sales on AfroTransact protects you and your customers."
}

func quote(s string) string {
	r := []rune(s)
	if len(r) > 40 {
		s = string(r[:40]) + "..."
	}
	return "\"" + s + "\""
}
