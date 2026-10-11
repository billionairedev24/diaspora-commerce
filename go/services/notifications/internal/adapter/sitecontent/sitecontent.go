// Package sitecontent keeps the email brand in step with the content service's public site configuration
// (GET /v1/site-config, contracts/openapi/content.yaml): social links, the WhatsApp community link, app store links,
// the customer service phone and email, the postal address and the legal documents. It is the same source the
// storefront footer renders, so staff edit both in the staff console's site settings.
//
// The snapshot is refreshed in the background (ETag, 304 keeps it) and Brand never blocks: until the first fetch
// succeeds, and whenever content is unreachable, emails use the last good snapshot over the configured defaults
// (config brand.*).
package sitecontent

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// siteConfig is the part of content's public SiteConfig the emails use.
type siteConfig struct {
	Revision int `json:"revision"`
	Socials  []struct {
		Network string `json:"network"`
		URL     string `json:"url"`
		Label   string `json:"label"`
	} `json:"socials"`
	Contacts struct {
		Phone        string `json:"phone"`
		PhoneE164    string `json:"phoneE164"`
		SupportEmail string `json:"supportEmail"`
		Address      string `json:"address"`
	} `json:"contacts"`
	Legal []struct {
		Key   string `json:"key"`
		Title string `json:"title"`
		Href  string `json:"href"`
	} `json:"legal"`
	AppLinks struct {
		IOS     string `json:"ios"`
		Android string `json:"android"`
	} `json:"appLinks"`
	WhatsAppCommunityURL string `json:"whatsappCommunityUrl"`
}

// EmailLegalKeys are the legal documents linked from every email footer, in order (the rest stay on the site).
var EmailLegalKeys = []string{"privacy", "privacy-choices", "conditions-of-use", "sms-terms"}

// Source is the refreshing BrandSource.
type Source interface {
	port.BrandSource
	// Run refreshes the snapshot every interval until ctx ends.
	Run(ctx context.Context, every time.Duration)
	// Refresh fetches the site config once.
	Refresh(ctx context.Context) error
}

type sourceImpl struct {
	url      string
	client   *http.Client
	defaults domain.Brand
	log      *slog.Logger

	mu   sync.RWMutex
	snap domain.Brand
	etag string
}

var _ Source = (*sourceImpl)(nil)

// New returns the site-config BrandSource. siteConfigURL is content's GET /v1/site-config (empty = defaults only).
func New(siteConfigURL string, defaults domain.Brand, client *http.Client, log *slog.Logger) Source {
	return &sourceImpl{url: siteConfigURL, client: client, defaults: defaults, log: log, snap: defaults}
}

func (s *sourceImpl) Brand() domain.Brand {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.snap
}

func (s *sourceImpl) Run(ctx context.Context, every time.Duration) {
	if s.url == "" {
		return
	}
	if every <= 0 {
		every = 5 * time.Minute
	}
	t := time.NewTicker(every)
	defer t.Stop()
	for {
		if err := s.Refresh(ctx); err != nil && ctx.Err() == nil {
			s.log.WarnContext(ctx, "site config not refreshed; emails keep the last brand snapshot", "error", err)
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

func (s *sourceImpl) Refresh(ctx context.Context) error {
	if s.url == "" {
		return nil
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, s.url, nil)
	if err != nil {
		return fmt.Errorf("site config request: %w", err)
	}
	req.Header.Set("Accept", "application/json")
	s.mu.RLock()
	if s.etag != "" {
		req.Header.Set("If-None-Match", s.etag)
	}
	s.mu.RUnlock()
	res, err := s.client.Do(req)
	if err != nil {
		return fmt.Errorf("site config: %w", err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.StatusCode == http.StatusNotModified {
		return nil
	}
	if res.StatusCode != http.StatusOK {
		_, _ = io.Copy(io.Discard, io.LimitReader(res.Body, 4096))
		return fmt.Errorf("site config: status %d", res.StatusCode)
	}
	var c siteConfig
	if err := json.NewDecoder(io.LimitReader(res.Body, 1<<20)).Decode(&c); err != nil {
		return fmt.Errorf("site config: decode: %w", err)
	}
	b := s.defaults.Merge(ToBrand(c.toDomain()))
	s.mu.Lock()
	s.snap, s.etag = b, res.Header.Get("ETag")
	s.mu.Unlock()
	return nil
}

// Snapshot is the site config fields mapped to the brand (exported for tests).
type Snapshot struct {
	Revision             int
	Socials              []domain.SocialLink
	Phone, PhoneE164     string
	SupportEmail         string
	Address              string
	Legal                []domain.BrandLink
	IOS, Android         string
	WhatsAppCommunityURL string
}

func (c siteConfig) toDomain() Snapshot {
	out := Snapshot{Revision: c.Revision, Phone: c.Contacts.Phone, PhoneE164: c.Contacts.PhoneE164, SupportEmail: c.Contacts.SupportEmail,
		Address: c.Contacts.Address, IOS: c.AppLinks.IOS, Android: c.AppLinks.Android, WhatsAppCommunityURL: c.WhatsAppCommunityURL}
	for _, so := range c.Socials {
		out.Socials = append(out.Socials, domain.SocialLink{Network: strings.ToLower(so.Network), Label: so.Label, URL: so.URL})
	}
	byKey := map[string]domain.BrandLink{}
	for _, l := range c.Legal {
		byKey[l.Key] = domain.BrandLink{Label: l.Title, URL: l.Href}
	}
	for _, k := range EmailLegalKeys {
		if l, ok := byKey[k]; ok && l.Label != "" && l.URL != "" {
			out.Legal = append(out.Legal, l)
		}
	}
	return out
}

// ToBrand maps a site-config snapshot onto the brand fields it overrides (empty fields keep the defaults).
func ToBrand(s Snapshot) domain.Brand {
	return domain.Brand{Revision: s.Revision, Socials: s.Socials, SupportPhone: s.Phone, SupportPhoneE164: s.PhoneE164,
		SupportEmail: s.SupportEmail, Address: s.Address, Legal: s.Legal, AppStoreURL: s.IOS, PlayStoreURL: s.Android,
		WhatsAppCommunityURL: s.WhatsAppCommunityURL}
}
