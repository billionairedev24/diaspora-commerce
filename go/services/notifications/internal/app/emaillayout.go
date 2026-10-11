package app

import (
	"bytes"
	"embed"
	"encoding/json"
	"fmt"
	htmltemplate "html/template"
	"regexp"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// Reserved template data keys the notifier sets for the email layout. Content templates never need them.
const (
	// DataAdvertorials holds the promotions shown below the main content ([]domain.Advertorial, or the same as JSON).
	DataAdvertorials = "_advertorials"
	// DataUnsubscribeURL is the signed one-click unsubscribe link of a marketing email.
	DataUnsubscribeURL = "_unsubscribeUrl"
)

//go:embed email/layout.html.tmpl
var layoutFS embed.FS

// Email palette (web/packages/tokens): the layout and the components share it.
const (
	colorGold      = "#FFD400" // gold-500, primary action
	colorInk       = "#191816" // ink-900, text
	colorBlack     = "#000000" // ink-1000, header and footer
	colorGreen     = "#067457" // green-600, success and the WhatsApp block
	colorGreenText = "#055E47" // green-700, links and success text on white
	colorGreenBg   = "#E8F5F0" // green-50
	colorGoldBg    = "#FFFBE6" // gold-50
	colorGoldText  = "#7A6400" // gold-800, warning text
	colorRed       = "#B42318" // red-600
	colorRedBg     = "#FDEDEC" // red-50
	colorMuted     = "#69645B" // ink-550
	colorLine      = "#E0DCD2" // ink-200
	colorCanvas    = "#F6F4EF" // ink-50
	fontStack      = "Figtree,'Segoe UI',Helvetica,Arial,sans-serif"
)

// IsFullDocument reports whether an email body is a complete HTML document (a legacy version, or compiled
// React Email / MJML pasted as-is). Those are sent unchanged; every other body is a content fragment rendered
// inside the shared layout.
func IsFullDocument(body string) bool {
	head := strings.ToLower(strings.TrimSpace(body))
	if len(head) > 512 {
		head = head[:512]
	}
	return strings.HasPrefix(head, "<!doctype") || strings.Contains(head, "<html")
}

// layoutFuncs are the helpers content templates may call: {{button url label}}, {{secondaryButton url label}},
// {{otp code}}, {{callout tone text}}, {{facts "Label" value …}}, {{badge tone text}}, plus dict/list for the
// packages, totals and address components.
func layoutFuncs() htmltemplate.FuncMap {
	return htmltemplate.FuncMap{
		"button": func(url, label string) htmltemplate.HTML {
			return bulletproofButton(url, label, colorGold, colorBlack, true)
		},
		"darkButton": func(url, label string) htmltemplate.HTML {
			return bulletproofButton(url, label, colorBlack, colorGold, true)
		},
		"secondaryButton":   secondaryButton,
		"otp":               otpBox,
		"callout":           callout,
		"badge":             badge,
		"facts":             facts,
		"dict":              dict,
		"list":              func(v ...any) []any { return v },
		"msoHead":           func() htmltemplate.HTML { return msoHead },
		"msoContainerStart": func() htmltemplate.HTML { return msoContainerStart },
		"msoContainerEnd":   func() htmltemplate.HTML { return msoContainerEnd },
		"preheaderPad":      func() htmltemplate.HTML { return htmltemplate.HTML(strings.Repeat("&#847;&zwnj;&nbsp;", 60)) }, //nolint:gosec // fixed markup
		"waButton": func(url string) htmltemplate.HTML {
			return bulletproofButton(url, "Join on WhatsApp", "#FFFFFF", colorGreenText, false)
		},
		// placeholderImage is replaced per render with the brand's hosted placeholder.
		"placeholderImage": func() string { return "" },
	}
}

const (
	msoHead           = htmltemplate.HTML(`<!--[if mso]><noscript><xml><o:OfficeDocumentSettings><o:AllowPNG/><o:PixelsPerInch>96</o:PixelsPerInch></o:OfficeDocumentSettings></xml></noscript><style>td,th,div,p,a,h1,h2,h3{font-family:Arial,Helvetica,sans-serif !important;}</style><![endif]-->`)
	msoContainerStart = htmltemplate.HTML(`<!--[if mso]><table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0" align="center"><tr><td><![endif]-->`)
	msoContainerEnd   = htmltemplate.HTML(`<!--[if mso]></td></tr></table><![endif]-->`)
)

func esc(s string) string { return htmltemplate.HTMLEscapeString(s) }

// bulletproofButton is a padded-cell button (every client) with a VML roundrect for Outlook on Windows. Unsafe or
// empty URLs render nothing.
func bulletproofButton(url, label, bg, fg string, full bool) htmltemplate.HTML {
	if !domain.SafeURL(url) || strings.TrimSpace(label) == "" {
		return ""
	}
	u, l := esc(url), esc(label)
	width := 220
	if n := len([]rune(label))*9 + 56; n > width {
		width = n
	}
	tableClass := ""
	if full {
		tableClass = ` class="at-btn-table"`
	}
	var b strings.Builder
	fmt.Fprintf(&b, `<table role="presentation" cellpadding="0" cellspacing="0" border="0"%s style="margin:8px 0 24px 0;"><tr><td align="center" bgcolor="%s" style="border-radius:999px;background-color:%s;">`, tableClass, bg, bg)
	fmt.Fprintf(&b, `<!--[if mso]><v:roundrect xmlns:v="urn:schemas-microsoft-com:vml" xmlns:w="urn:schemas-microsoft-com:office:word" href="%s" style="height:48px;v-text-anchor:middle;width:%dpx;" arcsize="50%%" stroke="f" fillcolor="%s"><w:anchorlock/><center style="color:%s;font-family:Arial,sans-serif;font-size:16px;font-weight:bold;">%s</center></v:roundrect><![endif]-->`, u, width, bg, fg, l)
	fmt.Fprintf(&b, `<!--[if !mso]><!--><a href="%s" class="at-btn" style="display:inline-block;background-color:%s;color:%s;font-family:%s;font-size:16px;line-height:20px;font-weight:800;text-decoration:none;padding:14px 32px;border-radius:999px;mso-hide:all;">%s</a><!--<![endif]-->`, u, bg, fg, fontStack, l)
	b.WriteString(`</td></tr></table>`)
	return htmltemplate.HTML(b.String()) //nolint:gosec // every dynamic value is escaped above
}

func secondaryButton(url, label string) htmltemplate.HTML {
	if !domain.SafeURL(url) || strings.TrimSpace(label) == "" {
		return ""
	}
	return htmltemplate.HTML(fmt.Sprintf(`<p style="margin:0 0 20px 0;"><a href="%s" class="at-link" style="display:inline-block;border:2px solid %s;color:%s;font-family:%s;font-size:15px;line-height:20px;font-weight:700;text-decoration:none;padding:10px 22px;border-radius:999px;">%s</a></p>`, //nolint:gosec // escaped
		esc(url), colorInk, colorInk, fontStack, esc(label)))
}

func otpBox(code string) htmltemplate.HTML {
	return htmltemplate.HTML(fmt.Sprintf(`<table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin:8px 0 24px 0;"><tr><td class="at-panel" style="background-color:%s;border:2px dashed %s;border-radius:12px;padding:16px 28px;font-family:'SF Mono',Menlo,Consolas,monospace;font-size:34px;line-height:40px;font-weight:800;letter-spacing:10px;color:%s;">%s</td></tr></table>`, //nolint:gosec // escaped
		colorGoldBg, "#E6BF00", colorInk, esc(code)))
}

var calloutTones = map[string][3]string{ // background, border/text, icon
	"success": {colorGreenBg, colorGreenText, "&#10003;"},
	"info":    {colorCanvas, colorInk, "i"},
	"warning": {colorGoldBg, colorGoldText, "!"},
	"danger":  {colorRedBg, colorRed, "!"},
}

func callout(tone, text string) htmltemplate.HTML {
	if strings.TrimSpace(text) == "" {
		return ""
	}
	t, ok := calloutTones[tone]
	if !ok {
		t = calloutTones["info"]
	}
	return htmltemplate.HTML(fmt.Sprintf(`<table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="margin:0 0 20px 0;"><tr><td class="at-panel" style="background-color:%s;border-left:4px solid %s;border-radius:8px;padding:12px 16px;font-family:%s;font-size:15px;line-height:22px;color:%s;"><strong style="color:%s;">%s</strong>&nbsp; %s</td></tr></table>`, //nolint:gosec // escaped
		t[0], t[1], fontStack, colorInk, t[1], t[2], esc(text)))
}

func badge(tone, text string) htmltemplate.HTML {
	t, ok := calloutTones[tone]
	if !ok {
		t = calloutTones["info"]
	}
	return htmltemplate.HTML(fmt.Sprintf(`<p style="margin:0 0 12px 0;"><span style="display:inline-block;background-color:%s;color:%s;font-family:%s;font-size:12px;line-height:16px;font-weight:800;letter-spacing:0.6px;text-transform:uppercase;padding:5px 10px;border-radius:999px;">%s</span></p>`, //nolint:gosec // escaped
		t[0], t[1], fontStack, esc(text)))
}

// facts renders label/value pairs as a two-column table, skipping empty values: {{facts "Order" .orderNumber "Total" .total}}.
func facts(pairs ...any) htmltemplate.HTML {
	var b strings.Builder
	rows := 0
	for i := 0; i+1 < len(pairs); i += 2 {
		label, value := fmt.Sprint(pairs[i]), strings.TrimSpace(fmt.Sprint(pairs[i+1]))
		if value == "" || value == "<nil>" {
			continue
		}
		fmt.Fprintf(&b, `<tr><td class="at-muted at-line" style="padding:8px 12px 8px 0;border-bottom:1px solid %s;color:%s;font-size:14px;line-height:20px;white-space:nowrap;vertical-align:top;">%s</td><td class="at-text at-line" style="padding:8px 0;border-bottom:1px solid %s;color:%s;font-size:15px;line-height:20px;font-weight:700;">%s</td></tr>`,
			colorLine, colorMuted, esc(label), colorLine, colorInk, esc(value))
		rows++
	}
	if rows == 0 {
		return ""
	}
	return htmltemplate.HTML(`<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="margin:0 0 20px 0;font-family:` + fontStack + `;">` + b.String() + `</table>`) //nolint:gosec // escaped
}

func dict(kv ...any) (map[string]any, error) {
	if len(kv)%2 != 0 {
		return nil, fmt.Errorf("dict: odd number of arguments")
	}
	out := make(map[string]any, len(kv)/2)
	for i := 0; i < len(kv); i += 2 {
		k, ok := kv[i].(string)
		if !ok {
			return nil, fmt.Errorf("dict: key %v is not a string", kv[i])
		}
		out[k] = kv[i+1]
	}
	return out, nil
}

// tagDefaults are the inline styles bare tags in a content fragment get (a tag with its own style is left alone),
// so templates stay readable (<p>, <h1>, <a>) and still render the same in every client.
var tagDefaults = map[string]string{
	"h1":     `class="at-h1" style="margin:0 0 12px 0;font-family:` + fontStack + `;font-size:28px;line-height:34px;font-weight:800;color:` + colorInk + `;"`,
	"h2":     `style="margin:28px 0 12px 0;font-family:` + fontStack + `;font-size:19px;line-height:26px;font-weight:800;color:` + colorInk + `;"`,
	"h3":     `style="margin:20px 0 8px 0;font-family:` + fontStack + `;font-size:16px;line-height:22px;font-weight:800;color:` + colorInk + `;"`,
	"p":      `style="margin:0 0 16px 0;font-family:` + fontStack + `;font-size:16px;line-height:24px;color:` + colorInk + `;"`,
	"ul":     `style="margin:0 0 16px 0;padding:0 0 0 22px;font-family:` + fontStack + `;font-size:16px;line-height:24px;color:` + colorInk + `;"`,
	"ol":     `style="margin:0 0 16px 0;padding:0 0 0 22px;font-family:` + fontStack + `;font-size:16px;line-height:24px;color:` + colorInk + `;"`,
	"li":     `style="margin:0 0 6px 0;"`,
	"a":      `class="at-link" style="color:` + colorGreenText + `;font-weight:700;text-decoration:underline;"`,
	"small":  `class="at-muted" style="font-size:13px;line-height:18px;color:` + colorMuted + `;"`,
	"strong": `style="font-weight:800;"`,
}

var bareTag = regexp.MustCompile(`<(h1|h2|h3|p|ul|ol|li|a|small|strong)(\s[^>]*)?>`)

// inlineDefaults adds tagDefaults to tags without a style attribute.
func inlineDefaults(html string) string {
	return bareTag.ReplaceAllStringFunc(html, func(tag string) string {
		m := bareTag.FindStringSubmatch(tag)
		name, attrs := m[1], m[2]
		if strings.Contains(attrs, "style=") {
			return tag
		}
		return "<" + name + " " + tagDefaults[name] + attrs + ">"
	})
}

// layoutLink is a labelled link in the header or footer.
type layoutLink struct {
	Label string
	URL   string
}

type layoutSocial struct {
	Label, URL, Icon string
}

// layoutView is the data the layout template renders.
type layoutView struct {
	Lang, Subject, Preheader                string
	Content                                 htmltemplate.HTML
	Brand                                   domain.Brand
	HomeURL, LogoURL, HelpURL               string
	HeaderLinks, FooterLinks                []layoutLink
	Socials                                 []layoutSocial
	WhatsAppURL, WhatsAppIcon               string
	AppStoreURL, PlayStoreURL               string
	AppStoreBadge, PlayStoreBadge           string
	Ads                                     []domain.Advertorial
	Marketing                               bool
	UnsubscribeURL, PreferencesURL, WhyText string
	LegalName                               string
	Year                                    int
}

// layoutBuilder renders content fragments inside the shared layout.
type layoutBuilder struct {
	base  *htmltemplate.Template
	brand port.BrandSource
	now   func() time.Time
}

func newLayoutBuilder(brand port.BrandSource, now func() time.Time) *layoutBuilder {
	base := htmltemplate.Must(htmltemplate.New("layout.html.tmpl").Funcs(layoutFuncs()).ParseFS(layoutFS, "email/layout.html.tmpl"))
	return &layoutBuilder{base: base, brand: brand, now: now}
}

// parse compiles a content fragment together with the components, for one render.
func (l *layoutBuilder) parse(body string, b domain.Brand) (*htmltemplate.Template, error) {
	set, err := l.base.Clone()
	if err != nil {
		return nil, fmt.Errorf("clone layout: %w", err)
	}
	set.Funcs(htmltemplate.FuncMap{"placeholderImage": func() string { return b.Asset("placeholder-product.png") }})
	content, err := set.New("content").Option("missingkey=error").Parse(body)
	if err != nil {
		return nil, fmt.Errorf("parse: %w", err)
	}
	return content, nil
}

// render executes a content fragment with data and wraps it in the layout.
func (l *layoutBuilder) render(t domain.Template, subject string, data map[string]any) (string, error) {
	b := l.currentBrand()
	clean, ads, unsub := splitReserved(data)
	content, err := l.parse(t.BodyHTML, b)
	if err != nil {
		return "", err
	}
	var body bytes.Buffer
	if err := content.ExecuteTemplate(&body, "content", clean); err != nil {
		return "", fmt.Errorf("execute: %w", err)
	}
	pre := ""
	if content.Lookup("preheader") != nil {
		var p bytes.Buffer
		if err := content.ExecuteTemplate(&p, "preheader", clean); err != nil {
			return "", fmt.Errorf("preheader: %w", err)
		}
		pre = strings.Join(strings.Fields(p.String()), " ")
	}
	view := l.view(t, b, subject, pre, htmltemplate.HTML(inlineDefaults(body.String())), ads, unsub, clean) //nolint:gosec // html/template output
	var out bytes.Buffer
	if err := content.ExecuteTemplate(&out, "layout", view); err != nil {
		return "", fmt.Errorf("layout: %w", err)
	}
	return out.String(), nil
}

func (l *layoutBuilder) currentBrand() domain.Brand {
	b := domain.Brand{Name: "AfroTransact"}
	if l.brand != nil {
		b = b.Merge(l.brand.Brand())
	}
	if b.Name == "" {
		b.Name = "AfroTransact"
	}
	return b
}

func (l *layoutBuilder) view(t domain.Template, b domain.Brand, subject, preheader string, content htmltemplate.HTML,
	ads []domain.Advertorial, unsub string, data map[string]any) layoutView {
	home := b.Link("/")
	if b.StorefrontURL == "" {
		home = ""
	}
	v := layoutView{Lang: firstNonEmptyStr(langOf(t.Locale), "en"), Subject: subject, Preheader: preheader, Content: content, Brand: b,
		HomeURL: home, LogoURL: b.Asset("logo-header.png"), HelpURL: b.Link("/help"),
		PreferencesURL: b.Link("/account/security"), LegalName: b.Name + ", Inc.", Year: l.now().Year()}
	if b.StorefrontURL != "" {
		v.HeaderLinks = []layoutLink{{"Shop", b.Link("/")}, {"Orders", b.Link("/orders")}, {"Help", b.Link("/help")}}
	} else {
		v.HelpURL, v.PreferencesURL = "", ""
	}
	for _, s := range b.VisibleSocials() {
		v.Socials = append(v.Socials, layoutSocial{Label: s.Label, URL: s.URL, Icon: b.Asset("social/" + s.Network + ".png")})
	}
	if domain.SafeURL(b.WhatsAppCommunityURL) {
		v.WhatsAppURL, v.WhatsAppIcon = b.WhatsAppCommunityURL, b.Asset("social/whatsapp-community.png")
	}
	if domain.SafeURL(b.AppStoreURL) {
		v.AppStoreURL, v.AppStoreBadge = b.AppStoreURL, b.Asset("badges/app-store.png")
	}
	if domain.SafeURL(b.PlayStoreURL) {
		v.PlayStoreURL, v.PlayStoreBadge = b.PlayStoreURL, b.Asset("badges/google-play.png")
	}
	if t.Category != domain.CategorySecurity {
		for _, a := range ads {
			if a.Valid() {
				v.Ads = append(v.Ads, a)
			}
		}
	}
	// Promotional: marketing templates, and messages whose producer manages the consent (pre-order alerts carry
	// their own unsubscribeUrl): both get the unsubscribe link and the CAN-SPAM wording.
	v.Marketing = t.Category == domain.CategoryMarketing || safeStr(data, "unsubscribeUrl") != ""
	// The recipient's own unsubscribe link: the signed one-click link, else the producer's (pre-order consents),
	// else the preferences page.
	v.UnsubscribeURL = firstNonEmptyStr(unsub, safeStr(data, "unsubscribeUrl"), v.PreferencesURL)
	switch {
	case t.Category == domain.CategorySecurity:
		v.WhyText = "We sent this security email to protect your " + b.Name + " account. We will never ask for your password or a code by phone, text or email."
	case v.Marketing:
		v.WhyText = "This is a promotional email from " + b.Name + ". You're receiving it because you signed up for news, offers or pre-order updates."
	case t.Category == domain.CategoryReminder:
		v.WhyText = "You're receiving this reminder about your " + b.Name + " account. You can choose which reminders you get in your email preferences."
	default:
		v.WhyText = "You're receiving this service email about your " + b.Name + " account, orders or bookings. It is sent even if you have opted out of promotional email."
	}
	if v.PreferencesURL != "" {
		v.FooterLinks = append(v.FooterLinks, layoutLink{"Email preferences", v.PreferencesURL})
	}
	if v.Marketing && v.UnsubscribeURL != "" {
		v.FooterLinks = append(v.FooterLinks, layoutLink{"Unsubscribe", v.UnsubscribeURL})
	}
	for _, lg := range b.Legal {
		if u := b.Link(lg.URL); u != "" && lg.Label != "" {
			v.FooterLinks = append(v.FooterLinks, layoutLink{lg.Label, u})
		}
	}
	return v
}

// textFooter is appended to the plain-text part of every layout email.
func (l *layoutBuilder) textFooter(t domain.Template, data map[string]any) string {
	b := l.currentBrand()
	_, _, unsub := splitReserved(data)
	var lines []string
	if b.SupportPhone != "" {
		lines = append(lines, "Questions? Call "+b.SupportPhone+".")
	}
	if b.StorefrontURL != "" {
		lines = append(lines, "Help Center: "+b.Link("/help"))
	}
	if domain.SafeURL(b.WhatsAppCommunityURL) {
		lines = append(lines, "Join our WhatsApp community: "+b.WhatsAppCommunityURL)
	}
	if t.Category == domain.CategoryMarketing {
		u := firstNonEmptyStr(unsub, safeStr(data, "unsubscribeUrl"))
		if u == "" && b.StorefrontURL != "" {
			u = b.Link("/account/security")
		}
		if u != "" {
			lines = append(lines, "Unsubscribe: "+u)
		}
	} else if b.StorefrontURL != "" {
		lines = append(lines, "Email preferences: "+b.Link("/account/security"))
	}
	if b.Address != "" {
		lines = append(lines, b.Address)
	}
	if len(lines) == 0 {
		return ""
	}
	return "\n\n--\n" + strings.Join(lines, "\n")
}

// splitReserved separates the layout's reserved keys from the content's data.
func splitReserved(data map[string]any) (clean map[string]any, ads []domain.Advertorial, unsub string) {
	clean = make(map[string]any, len(data))
	for k, v := range data {
		if k != DataAdvertorials && k != DataUnsubscribeURL {
			clean[k] = v
		}
	}
	unsub, _ = data[DataUnsubscribeURL].(string)
	if !domain.SafeURL(unsub) {
		unsub = ""
	}
	switch v := data[DataAdvertorials].(type) {
	case []domain.Advertorial:
		ads = v
	case nil:
	default: // JSON (staff console sample data, stored requests)
		raw, err := json.Marshal(v)
		if err == nil {
			_ = json.Unmarshal(raw, &ads)
		}
	}
	return clean, ads, unsub
}

func safeStr(d map[string]any, k string) string {
	if s := str(d, k); domain.SafeURL(s) {
		return s
	}
	return ""
}

func langOf(locale string) string {
	lang, _, _ := strings.Cut(locale, "-")
	return strings.ToLower(lang)
}
