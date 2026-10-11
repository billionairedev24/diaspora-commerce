package domain

import (
	"net/url"
	"strings"
)

// Brand is what the shared email layout shows around every email: the logo, the footer's social accounts, the
// WhatsApp community invitation, app store links, the customer service phone, the postal address and the legal
// links. It is a snapshot of the content service's site configuration (GET /v1/site-config) over the defaults in
// config/<env>.yaml (brand.*), so staff change the footer in the staff console without a deployment.
type Brand struct {
	Name string `json:"name"`
	// StorefrontURL is the shop's public base URL; relative links in the site config are resolved against it.
	StorefrontURL string `json:"storefrontUrl"`
	// AssetBaseURL hosts the email images (logo, social icons, store badges): PNGs, never SVG or data URIs.
	AssetBaseURL         string       `json:"assetBaseUrl"`
	Socials              []SocialLink `json:"socials"`
	WhatsAppCommunityURL string       `json:"whatsappCommunityUrl"`
	AppStoreURL          string       `json:"appStoreUrl"`
	PlayStoreURL         string       `json:"playStoreUrl"`
	SupportPhone         string       `json:"supportPhone"`
	SupportPhoneE164     string       `json:"supportPhoneE164"`
	SupportEmail         string       `json:"supportEmail"`
	Address              string       `json:"address"`
	Legal                []BrandLink  `json:"legal"`
	// Revision is the site-config revision the snapshot came from (0 = config defaults only).
	Revision int `json:"revision"`
}

// SocialLink is one social account in the footer.
type SocialLink struct {
	Network string `json:"network"`
	Label   string `json:"label"`
	URL     string `json:"url"`
}

// BrandLink is a labelled footer link (legal documents).
type BrandLink struct {
	Label string `json:"label"`
	URL   string `json:"url"`
}

// SocialNetworks are the networks the footer has icons for, in display order (design/at-site.js).
var SocialNetworks = []string{"tiktok", "instagram", "facebook", "x", "youtube", "linkedin", "whatsapp"}

var socialLabels = map[string]string{"tiktok": "TikTok", "instagram": "Instagram", "facebook": "Facebook", "x": "X",
	"youtube": "YouTube", "linkedin": "LinkedIn", "whatsapp": "WhatsApp"}

// SocialLabel is a network's display name ("youtube" → "YouTube").
func SocialLabel(network string) string {
	if l, ok := socialLabels[network]; ok {
		return l
	}
	return network
}

// Asset returns the URL of an email image under the asset base ("social/tiktok.png").
func (b Brand) Asset(path string) string {
	return strings.TrimRight(b.AssetBaseURL, "/") + "/" + strings.TrimLeft(path, "/")
}

// Link resolves a site path ("/legal/privacy") against the storefront; absolute http(s) URLs are kept.
func (b Brand) Link(path string) string {
	if path == "" {
		return ""
	}
	if SafeURL(path) {
		return path
	}
	return strings.TrimRight(b.StorefrontURL, "/") + "/" + strings.TrimLeft(path, "/")
}

// Merge overlays the non-empty fields of over (a site-config snapshot) on b (the configured defaults).
func (b Brand) Merge(over Brand) Brand {
	pick := func(a, o string) string {
		if strings.TrimSpace(o) != "" {
			return strings.TrimSpace(o)
		}
		return a
	}
	out := b
	out.Name = pick(b.Name, over.Name)
	out.StorefrontURL = pick(b.StorefrontURL, over.StorefrontURL)
	out.AssetBaseURL = pick(b.AssetBaseURL, over.AssetBaseURL)
	out.WhatsAppCommunityURL = pick(b.WhatsAppCommunityURL, over.WhatsAppCommunityURL)
	out.AppStoreURL = pick(b.AppStoreURL, over.AppStoreURL)
	out.PlayStoreURL = pick(b.PlayStoreURL, over.PlayStoreURL)
	out.SupportPhone = pick(b.SupportPhone, over.SupportPhone)
	out.SupportPhoneE164 = pick(b.SupportPhoneE164, over.SupportPhoneE164)
	out.SupportEmail = pick(b.SupportEmail, over.SupportEmail)
	out.Address = pick(b.Address, over.Address)
	if len(over.Socials) > 0 {
		out.Socials = over.Socials
	}
	if len(over.Legal) > 0 {
		out.Legal = over.Legal
	}
	if over.Revision > 0 {
		out.Revision = over.Revision
	}
	return out
}

// VisibleSocials are the footer's social links: known networks with an https URL, in display order, once each.
func (b Brand) VisibleSocials() []SocialLink {
	byNet := map[string]SocialLink{}
	for _, s := range b.Socials {
		n := strings.ToLower(strings.TrimSpace(s.Network))
		if _, known := socialLabels[n]; !known || !strings.HasPrefix(s.URL, "https://") {
			continue
		}
		if _, dup := byNet[n]; !dup {
			byNet[n] = SocialLink{Network: n, Label: SocialLabel(n), URL: s.URL}
		}
	}
	out := make([]SocialLink, 0, len(byNet))
	for _, n := range SocialNetworks {
		if s, ok := byNet[n]; ok {
			out = append(out, s)
		}
	}
	return out
}

// SafeURL reports whether u is an absolute http(s) URL with a host (what an email may link to or load).
func SafeURL(u string) bool {
	p, err := url.Parse(strings.TrimSpace(u))
	return err == nil && (p.Scheme == "https" || p.Scheme == "http") && p.Host != ""
}

// Advertorial is a promotion shown below the main content of an email: related products ("Complete your
// pantry"), an open pre-order in the customer's city, or a house campaign from the content service. Advertorials
// never appear in security emails, nor for recipients who opted out of marketing email.
type Advertorial struct {
	// Kind is products, preorder or campaign.
	Kind     string      `json:"kind"`
	Eyebrow  string      `json:"eyebrow,omitempty"`
	Title    string      `json:"title"`
	Body     string      `json:"body,omitempty"`
	ImageURL string      `json:"imageUrl,omitempty"`
	ImageAlt string      `json:"imageAlt,omitempty"`
	CTALabel string      `json:"ctaLabel,omitempty"`
	CTAURL   string      `json:"ctaUrl,omitempty"`
	Products []PromoItem `json:"products,omitempty"`
	// Sponsor names a third-party advertiser ("Sponsored · Tamale Naturals"); empty for AfroTransact's own.
	Sponsor string      `json:"sponsor,omitempty"`
	Source  PromoSource `json:"source"`
}

// PromoItem is one product in a products advertorial.
type PromoItem struct {
	Title    string `json:"title"`
	ImageURL string `json:"imageUrl"`
	Price    string `json:"price"`
	URL      string `json:"url"`
}

// PromoSource records where an advertorial came from (search, delivery-runs, content placement id).
type PromoSource struct {
	Service string `json:"service,omitempty"`
	ID      string `json:"id,omitempty"`
}

// Advertorial kinds.
const (
	AdProducts = "products"
	AdPreorder = "preorder"
	AdCampaign = "campaign"
)

// Valid reports whether an advertorial can be shown: a known kind, a title, safe links and images, and products
// with an image and a link.
func (a Advertorial) Valid() bool {
	if strings.TrimSpace(a.Title) == "" {
		return false
	}
	if a.CTAURL != "" && !SafeURL(a.CTAURL) || a.ImageURL != "" && !SafeURL(a.ImageURL) {
		return false
	}
	switch a.Kind {
	case AdProducts:
		if len(a.Products) == 0 {
			return false
		}
		for _, p := range a.Products {
			if p.Title == "" || !SafeURL(p.URL) || !SafeURL(p.ImageURL) {
				return false
			}
		}
		return true
	case AdPreorder, AdCampaign:
		return a.CTAURL != ""
	default:
		return false
	}
}

// PromoProduct is a product found for a promotion.
type PromoProduct struct {
	Atin       string `json:"atin"`
	Title      string `json:"title"`
	ImageURL   string `json:"imageUrl"`
	PriceMinor int64  `json:"priceMinor"`
	Currency   string `json:"currency"`
}

// OpenPreorder is a published pre-order window.
type OpenPreorder struct {
	ID      string `json:"id"`
	Code    string `json:"code"`
	Name    string `json:"name"`
	Where   string `json:"where"`
	City    string `json:"city"`
	Region  string `json:"region"`
	Country string `json:"country"`
	Stage   string `json:"stage"`
	Open    bool   `json:"open"`
	HeroURL string `json:"heroUrl"`
	Label   string `json:"label"`
}

// Campaign is a live house campaign (a content placement).
type Campaign struct {
	ID       string `json:"id"`
	Kicker   string `json:"kicker"`
	Title    string `json:"title"`
	Body     string `json:"body"`
	ImageURL string `json:"imageUrl"`
	ImageAlt string `json:"imageAlt"`
	CTA      string `json:"cta"`
	Href     string `json:"href"`
	Sponsor  string `json:"sponsor"`
}
