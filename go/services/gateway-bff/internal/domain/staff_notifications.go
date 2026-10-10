package domain

import (
	"regexp"
	"slices"
	"strings"
	"time"
	"unicode/utf16"
)

// Notification templates in the staff console (Notifications → Templates, S268; contracts/openapi/gateway-bff-staff.yaml,
// tag Notifications) over notifications' versioned templates.

// Notification permissions (notifications service).
const (
	PermNotificationsRead           = "notifications:read"
	PermNotificationsTemplatesWrite = "notifications:templates:write"
)

// MaxSmsSegments is the longest SMS a new template version may render to (with the key's sample data).
const MaxSmsSegments = 3

// NotificationChannels are the template channels, in display order ("chat" is WhatsApp).
var NotificationChannels = []string{"email", "sms", "push", "chat"}

// NotificationCategories decide consent rules (security always sends; marketing needs opt-in).
var NotificationCategories = []string{"security", "transactional", "reminder", "marketing"}

// NotificationTemplateSummary is one template key in the list: its channels, locales and latest version.
type NotificationTemplateSummary struct {
	Key           string     `json:"key"`
	Category      string     `json:"category"`
	Channels      []string   `json:"channels"`
	Locales       []string   `json:"locales"`
	LatestVersion int        `json:"latestVersion"`
	UpdatedBy     *string    `json:"updatedBy"`
	UpdatedAt     *time.Time `json:"updatedAt"`
}

// NotificationTemplateList is the Templates tab.
type NotificationTemplateList struct {
	Items   []NotificationTemplateSummary `json:"items"`
	Total   int                           `json:"total"`
	CanEdit bool                          `json:"canEdit"`
}

// NotificationTemplateVersion is one stored version of a key, locale and channel.
type NotificationTemplateVersion struct {
	Locale    string     `json:"locale"`
	Channel   string     `json:"channel"`
	Version   int        `json:"version"`
	Category  string     `json:"category"`
	Subject   string     `json:"subject"`
	BodyHTML  string     `json:"bodyHtml"`
	BodyText  string     `json:"bodyText"`
	CreatedBy *string    `json:"createdBy"`
	CreatedAt *time.Time `json:"createdAt"`
}

// NotificationTemplateVariant is every version of one locale and channel of a key, newest first.
type NotificationTemplateVariant struct {
	Locale        string                        `json:"locale"`
	Channel       string                        `json:"channel"`
	LatestVersion int                           `json:"latestVersion"`
	Versions      []NotificationTemplateVersion `json:"versions"`
}

// NotificationSample is the data a key renders with in previews (notifications GET /v1/templates/{key}/sample).
type NotificationSample struct {
	Source     string         `json:"source"`
	Area       string         `json:"area"`
	EventTypes []string       `json:"eventTypes"`
	Data       map[string]any `json:"data"`
}

// NotificationTemplateDetail is the template screen: variants with their history, sample data and what the staff
// member may do.
type NotificationTemplateDetail struct {
	Key         string                        `json:"key"`
	Category    string                        `json:"category"`
	Variants    []NotificationTemplateVariant `json:"variants"`
	Sample      NotificationSample            `json:"sample"`
	CanEdit     bool                          `json:"canEdit"`
	CanTestSend bool                          `json:"canTestSend"`
}

// NotificationDraft is unsaved template content (the editor's live preview and test-send).
type NotificationDraft struct {
	Category string `json:"category,omitempty"`
	Subject  string `json:"subject,omitempty"`
	BodyHTML string `json:"bodyHtml,omitempty"`
	BodyText string `json:"bodyText"`
}

// NotificationPreviewRequest renders a stored version (0 = latest) or a draft with data.
type NotificationPreviewRequest struct {
	Channel string             `json:"channel"`
	Locale  string             `json:"locale"`
	Version int                `json:"version"`
	Data    map[string]any     `json:"data"`
	Draft   *NotificationDraft `json:"draft,omitempty"`
}

// SmsSegments is how an SMS body is encoded and split.
type SmsSegments struct {
	Encoding   string `json:"encoding"`
	Characters int    `json:"characters"`
	Units      int    `json:"units"`
	Segments   int    `json:"segments"`
	PerSegment int    `json:"perSegment"`
}

// NotificationPreview is a rendered template.
type NotificationPreview struct {
	Subject  string       `json:"subject"`
	HTML     string       `json:"html"`
	Text     string       `json:"text"`
	Version  int          `json:"version"`
	Category string       `json:"category"`
	SMS      *SmsSegments `json:"sms"`
}

// NotificationVersionDraft creates the next version of a key, locale and channel.
type NotificationVersionDraft struct {
	Locale   string `json:"locale"`
	Channel  string `json:"channel"`
	Category string `json:"category"`
	Subject  string `json:"subject"`
	BodyHTML string `json:"bodyHtml"`
	BodyText string `json:"bodyText"`
}

// NotificationTestSendResult says where a test message went.
type NotificationTestSendResult struct {
	To       string  `json:"to"`
	Channel  string  `json:"channel"`
	Provider *string `json:"provider"`
}

var (
	templateKeyPattern    = regexp.MustCompile(`^[a-z0-9][a-z0-9_.-]{0,119}$`)
	templateLocalePattern = regexp.MustCompile(`^[a-z]{2}(-[A-Z]{2})?$`)
	templateAction        = regexp.MustCompile(`\{\{-?\s*(.*?)\s*-?\}\}`)
	templateFieldRef      = regexp.MustCompile(`(^|[^\w$])\$?\.([A-Za-z_][A-Za-z0-9_]*)`)
)

// ValidTemplateKey reports whether key looks like a template key ("order.confirmed").
func ValidTemplateKey(key string) bool { return templateKeyPattern.MatchString(key) }

// ValidTemplateLocale reports whether locale is "en" or "fr-CA" shaped.
func ValidTemplateLocale(locale string) bool { return templateLocalePattern.MatchString(locale) }

// TemplatePlaceholders returns the top-level data fields a Go template reads ({{.firstName}}, {{if .note}},
// {{$.total}}), sorted and unique. References inside {{range}} / {{with}} blocks are relative to the element and
// are skipped unless they start at the root ($.).
func TemplatePlaceholders(src string) []string {
	var out []string
	var stack []string
	for _, m := range templateAction.FindAllStringSubmatch(src, -1) {
		action := strings.TrimSpace(m[1])
		if strings.HasPrefix(action, "/*") {
			continue
		}
		word, _, _ := strings.Cut(action, " ")
		scoped := slices.Contains(stack, "range") || slices.Contains(stack, "with")
		if word == "end" {
			if len(stack) > 0 {
				stack = stack[:len(stack)-1]
			}
			continue
		}
		// A block's own pipeline ({{range .items}}) is evaluated in the enclosing scope; the block opens below.
		for _, ref := range templateFieldRef.FindAllStringSubmatch(action, -1) {
			root := strings.Contains(ref[0], "$.")
			if scoped && !root {
				continue
			}
			if !slices.Contains(out, ref[2]) {
				out = append(out, ref[2])
			}
		}
		if word == "if" || word == "range" || word == "with" || word == "block" || word == "define" {
			stack = append(stack, word)
		}
	}
	slices.Sort(out)
	return out
}

// gsm7Basic and gsm7Extension are the GSM 03.38 alphabets (extension characters take two septets).
const (
	gsm7Basic = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?" +
		"¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
	gsm7Extension = "^{}\\[~]|€\f"
)

// CountSmsSegments returns the encoding and segment count of an SMS body: GSM-7 when every character is in the
// GSM 03.38 alphabet (160 septets in one segment, 153 per part), else UCS-2 (70 / 67 UTF-16 code units). Same rules
// as notifications' domain.CountSmsSegments and the staff console's countSmsSegments.
func CountSmsSegments(body string) SmsSegments {
	chars := len([]rune(body))
	septets, gsm := 0, true
	for _, r := range body {
		switch {
		case strings.ContainsRune(gsm7Basic, r):
			septets++
		case strings.ContainsRune(gsm7Extension, r):
			septets += 2
		default:
			gsm = false
		}
		if !gsm {
			break
		}
	}
	if gsm {
		return splitSms("GSM-7", chars, septets, 160, 153)
	}
	return splitSms("UCS-2", chars, len(utf16.Encode([]rune(body))), 70, 67)
}

func splitSms(encoding string, chars, units, single, multi int) SmsSegments {
	out := SmsSegments{Encoding: encoding, Characters: chars, Units: units, PerSegment: single}
	switch {
	case units == 0:
	case units <= single:
		out.Segments = 1
	default:
		out.Segments, out.PerSegment = (units+multi-1)/multi, multi
	}
	return out
}
