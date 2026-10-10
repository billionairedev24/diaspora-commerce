package domain

import (
	"errors"
	"fmt"
	"net/url"
	"regexp"
	"slices"
	"strings"
	"time"
)

// Kind of organisation that owns a storefront.
type Kind string

// Storefront kinds.
const (
	KindSeller   Kind = "SELLER"
	KindProvider Kind = "PROVIDER"
)

// Status of a storefront.
type Status string

// Storefront statuses.
const (
	StatusDraft     Status = "DRAFT"
	StatusPublished Status = "PUBLISHED"
)

// Errors for storefront rules.
var (
	ErrInvalid    = errors.New("invalid storefront")
	ErrThemeFails = errors.New("theme fails contrast checks")
	ErrSlugTaken  = errors.New("store address is taken")
	ErrNotFound   = errors.New("storefront not found")
	ErrConflict   = errors.New("storefront changed concurrently")
)

// Section is one block of the page; Settings are section-specific (e.g. product ids, heading).
type Section struct {
	Type     string            `json:"type"`
	Enabled  bool              `json:"enabled"`
	Settings map[string]string `json:"settings,omitempty"`
}

// SectionTypes allowed per kind.
var SectionTypes = map[Kind][]string{
	KindSeller:   {"hero", "featured_products", "categories", "banner", "about", "reviews", "pickup_info"},
	KindProvider: {"hero", "services", "gallery", "featured_work", "about", "reviews", "social"},
}

// GalleryItem is a photo of the provider's work (media asset).
type GalleryItem struct {
	AssetID string `json:"assetId"`
	URL     string `json:"url"`
	Caption string `json:"caption,omitempty"`
}

// FeaturedPost is an advertorial of the provider's work, optionally linked to a bookable service.
type FeaturedPost struct {
	ID        string `json:"id"`
	Headline  string `json:"headline"`
	Body      string `json:"body"`
	AssetID   string `json:"assetId,omitempty"`
	URL       string `json:"url,omitempty"`
	ServiceID string `json:"serviceId,omitempty"`
	Published bool   `json:"published"`
}

// LinkStatus is the moderation state of a social link.
type LinkStatus string

// Link statuses: only approved links appear publicly.
const (
	LinkPending  LinkStatus = "PENDING"
	LinkApproved LinkStatus = "APPROVED"
	LinkRejected LinkStatus = "REJECTED"
)

// SocialLink is a provider/seller social or catalogue link, subject to business approval.
type SocialLink struct {
	Network string     `json:"network"`
	URL     string     `json:"url"`
	Status  LinkStatus `json:"status"`
}

// ProviderDesign holds provider page choices.
type ProviderDesign struct {
	Layout       string `json:"layout"` // CLASSIC | GALLERY_FIRST | STORY
	Header       string `json:"header"` // COVER | BAND
	CoverAssetID string `json:"coverAssetId,omitempty"`
	ButtonShape  string `json:"buttonShape"` // ROUNDED | SQUARE
	GallerySize  int    `json:"gallerySize"` // how many photos to show (≤ plan limit)
}

// Storefront is the aggregate.
type Storefront struct {
	OrgID       string            `json:"orgId"`
	Kind        Kind              `json:"kind"`
	Slug        string            `json:"slug"`
	Name        string            `json:"name"`
	Tagline     string            `json:"tagline"`
	Theme       ThemeInput        `json:"theme"`
	Tokens      map[string]string `json:"tokens,omitempty"`
	Sections    []Section         `json:"sections"`
	Provider    *ProviderDesign   `json:"provider,omitempty"`
	Gallery     []GalleryItem     `json:"gallery"`
	Featured    []FeaturedPost    `json:"featured"`
	Social      []SocialLink      `json:"social"`
	PlanCode    string            `json:"planCode"`
	Status      Status            `json:"status"`
	Version     int64             `json:"version"`
	PublishedAt *time.Time        `json:"publishedAt,omitempty"`
}

// Limits come from the platform's storefront settings and the org's plan.
type Limits struct {
	MaxSections      int
	MaxGallery       int // Starter 6, Growth 12, Pro 24
	MaxFeatured      int
	MaxTaglineLength int
}

var slugRe = regexp.MustCompile(`^[a-z0-9](?:[a-z0-9-]{1,38}[a-z0-9])$`)

// socialHosts maps networks to the hosts a link must point at.
var socialHosts = map[string][]string{
	"instagram": {"instagram.com", "www.instagram.com"},
	"tiktok":    {"tiktok.com", "www.tiktok.com"},
	"facebook":  {"facebook.com", "www.facebook.com", "fb.com"},
	"youtube":   {"youtube.com", "www.youtube.com", "youtu.be"},
	"whatsapp":  {"wa.me", "api.whatsapp.com", "whatsapp.com", "www.whatsapp.com"},
	"x":         {"x.com", "twitter.com", "www.x.com"},
	"linkedin":  {"linkedin.com", "www.linkedin.com"},
	"website":   nil, // any https host
}

// Validate checks the draft against the limits. It does not check theme contrast (Publish does).
func (s *Storefront) Validate(l Limits) error {
	var problems []error
	if !slugRe.MatchString(s.Slug) {
		problems = append(problems, errors.New("store address: 3–40 lowercase letters, digits and dashes"))
	}
	if n := len(strings.TrimSpace(s.Name)); n < 2 || n > 80 {
		problems = append(problems, errors.New("store name: 2–80 characters"))
	}
	if len(s.Tagline) > l.MaxTaglineLength {
		problems = append(problems, fmt.Errorf("tagline: at most %d characters", l.MaxTaglineLength))
	}
	if len(s.Sections) > l.MaxSections {
		problems = append(problems, fmt.Errorf("at most %d sections", l.MaxSections))
	}
	seen := map[string]bool{}
	for _, sec := range s.Sections {
		if !slices.Contains(SectionTypes[s.Kind], sec.Type) {
			problems = append(problems, fmt.Errorf("section %q is not available for %s pages", sec.Type, strings.ToLower(string(s.Kind))))
		}
		if seen[sec.Type] && sec.Type != "banner" {
			problems = append(problems, fmt.Errorf("section %q appears twice", sec.Type))
		}
		seen[sec.Type] = true
	}
	if s.Kind == KindProvider {
		problems = append(problems, s.validateProvider(l)...)
	}
	for i, link := range s.Social {
		if err := ValidateSocial(link.Network, link.URL); err != nil {
			problems = append(problems, fmt.Errorf("social link %d: %w", i+1, err))
		}
	}
	if len(problems) > 0 {
		return errors.Join(append([]error{ErrInvalid}, problems...)...)
	}
	return nil
}

func (s *Storefront) validateProvider(l Limits) []error {
	var problems []error
	p := s.Provider
	if p == nil {
		return []error{errors.New("provider page design is required")}
	}
	if !slices.Contains([]string{"CLASSIC", "GALLERY_FIRST", "STORY"}, p.Layout) {
		problems = append(problems, errors.New("layout must be CLASSIC, GALLERY_FIRST or STORY"))
	}
	if !slices.Contains([]string{"COVER", "BAND"}, p.Header) {
		problems = append(problems, errors.New("header must be COVER or BAND"))
	}
	if p.Header == "COVER" && p.CoverAssetID == "" {
		problems = append(problems, errors.New("a cover photo is required for the COVER header"))
	}
	if !slices.Contains([]string{"ROUNDED", "SQUARE"}, p.ButtonShape) {
		problems = append(problems, errors.New("button shape must be ROUNDED or SQUARE"))
	}
	if p.GallerySize < 0 || p.GallerySize > l.MaxGallery {
		problems = append(problems, fmt.Errorf("your plan shows up to %d gallery photos", l.MaxGallery))
	}
	if len(s.Gallery) > l.MaxGallery {
		problems = append(problems, fmt.Errorf("your plan allows up to %d gallery photos", l.MaxGallery))
	}
	if len(s.Featured) > l.MaxFeatured {
		problems = append(problems, fmt.Errorf("up to %d featured posts", l.MaxFeatured))
	}
	for i, f := range s.Featured {
		if n := len(strings.TrimSpace(f.Headline)); n < 3 || n > 80 {
			problems = append(problems, fmt.Errorf("featured post %d: headline 3–80 characters", i+1))
		}
		if len(f.Body) > 600 {
			problems = append(problems, fmt.Errorf("featured post %d: description up to 600 characters", i+1))
		}
	}
	return problems
}

// ValidateSocial checks that a link is https and points at the declared network.
func ValidateSocial(network, raw string) error {
	hosts, known := socialHosts[strings.ToLower(network)]
	if !known {
		return fmt.Errorf("unknown network %q", network)
	}
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "https" || u.Host == "" {
		return errors.New("link must be an https URL")
	}
	if hosts != nil && !slices.Contains(hosts, strings.ToLower(u.Host)) {
		return fmt.Errorf("link must point at %s", hosts[0])
	}
	return nil
}

// MergeSocial keeps the moderation status of unchanged links; new or edited links go back to PENDING.
func MergeSocial(previous, next []SocialLink) []SocialLink {
	out := make([]SocialLink, 0, len(next))
	for _, n := range next {
		n.Status = LinkPending
		for _, p := range previous {
			if strings.EqualFold(p.Network, n.Network) && p.URL == n.URL {
				n.Status = p.Status
			}
		}
		out = append(out, n)
	}
	return out
}

// PublicView is what shoppers see: only approved links, published posts and the gallery size chosen.
func (s *Storefront) PublicView() Storefront {
	v := *s
	v.Social = slices.DeleteFunc(slices.Clone(s.Social), func(l SocialLink) bool { return l.Status != LinkApproved })
	v.Featured = slices.DeleteFunc(slices.Clone(s.Featured), func(f FeaturedPost) bool { return !f.Published })
	v.Sections = slices.DeleteFunc(slices.Clone(s.Sections), func(sec Section) bool { return !sec.Enabled })
	if s.Provider != nil && len(v.Gallery) > s.Provider.GallerySize {
		v.Gallery = v.Gallery[:s.Provider.GallerySize]
	}
	v.Theme = ThemeInput{}
	return v
}
