package app

import (
	"context"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// weekdayOrder is Monday first, as the schedule is shown.
var weekdayOrder = []string{"MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"}

// plain is provider-authored text as the page may show it: plain text within a length, or "" when it looks like markup or is too long.
func plain(s string, max int, line bool) string {
	var out string
	var ok bool
	if line {
		out, ok = domain.SingleLine(s, max)
	} else {
		out, ok = domain.PlainText(s, max)
	}
	if !ok {
		return ""
	}
	return out
}

// buildProvider makes a provider's page: only real, public booking data from bookings. Services that are not in person
// (there are none: AfroTransact has no virtual services) would be dropped here too. Bookings being down leaves the page without
// services, schedule and rating rather than failing it; nothing is invented to fill the gap.
func (s *storefrontServiceImpl) buildProvider(ctx context.Context, src StorefrontSource) domain.Storefront {
	cfg := src.Config
	out := domain.Storefront{Kind: domain.StoreKindProvider, Slug: src.Slug, Name: src.Name, LogoURL: strPtr(src.LogoURL), Description: strPtr(src.Description),
		Config: cfg, Preview: src.Preview, Verified: true, Categories: []domain.StorefrontCategory{}, Featured: []domain.ProductSummary{},
		Products: domain.StorefrontProducts{Hits: []domain.ProductSummary{}, Page: 1, Size: storefrontPageSize, Categories: []domain.StorefrontCategory{}, Prices: []domain.StorefrontPrice{}}}
	if !src.Since.IsZero() {
		y := src.Since.Year()
		out.SellingSinceYear = &y
	}
	out.Config = publicConfig(cfg, src.WebsiteEarned)
	if s.bookings == nil {
		return out
	}
	p, err := s.bookings.Provider(ctx, src.OrgID)
	if err != nil {
		return out
	}
	prov := &domain.StorefrontProvider{OrgID: src.OrgID, TimeZone: p.TimeZone, Location: plain(p.Location, 120, true), InstantBooking: p.InstantBooking,
		PolicyLabel: plain(p.Policy.Label, 80, true), PolicyText: plain(p.Policy.Text, 600, false),
		Services: []domain.StorefrontService{}, Hours: []domain.StorefrontDayHours{}, Team: []domain.StorefrontMember{}}
	for _, sv := range p.Services {
		modes := []string{}
		for _, m := range sv.Modes {
			if m == "IN_PERSON" || m == "AT_HOME" {
				modes = append(modes, m)
			}
		}
		if len(modes) == 0 || strings.TrimSpace(sv.ID) == "" {
			continue
		}
		var img *string
		if sv.ImageURL != "" && domain.MediaURLOK(sv.ImageURL, s.policy) {
			u := sv.ImageURL
			img = &u
		}
		prov.Services = append(prov.Services, domain.StorefrontService{ID: sv.ID, Name: plain(sv.Name, 120, true), Description: plain(sv.Description, 600, false),
			Category: plain(sv.Category, 60, true), DurationMinutes: sv.DurationMinutes, PriceMinor: sv.PriceMinor, DepositMinor: sv.DepositMinor,
			Currency: firstNonEmpty(strings.ToUpper(sv.Currency), domain.Currency), Modes: modes, ImageURL: img})
	}
	byDay := map[string][]domain.StorefrontTimeSpan{}
	for _, d := range p.WeeklyHours {
		for _, r := range d.Ranges {
			byDay[strings.ToUpper(d.Day)] = append(byDay[strings.ToUpper(d.Day)], domain.StorefrontTimeSpan{Start: r.Start, End: r.End})
		}
	}
	anyOpen := false
	for _, day := range weekdayOrder {
		r := byDay[day]
		if r == nil {
			r = []domain.StorefrontTimeSpan{}
		}
		anyOpen = anyOpen || len(r) > 0
		prov.Hours = append(prov.Hours, domain.StorefrontDayHours{Day: day, Ranges: r})
	}
	if !anyOpen {
		prov.Hours = []domain.StorefrontDayHours{} // no hours set: the schedule is not shown, never "closed all week"
	}
	if a := p.ServiceArea; a != nil && strings.TrimSpace(a.BaseCity) != "" {
		prov.Area = &domain.StorefrontArea{BaseCity: plain(a.BaseCity, 80, true), BaseState: plain(a.BaseState, 2, true), RadiusMiles: a.RadiusMiles,
			ServedCities: cleanList(a.ServedCities, 12), ExtraAreas: cleanList(a.ExtraAreas, 20)}
	}
	for _, m := range p.Team {
		if name := plain(m.DisplayName, 80, true); name != "" && m.ID != "" {
			prov.Team = append(prov.Team, domain.StorefrontMember{ID: m.ID, DisplayName: name})
		}
	}
	out.Provider = prov
	out.Reviews = s.providerReviews(ctx, src.OrgID)
	out.Indexable = !src.Preview && len(prov.Services) > 0
	return out
}

func cleanList(in []string, max int) []string {
	out := []string{}
	for _, v := range in {
		if c := plain(v, 80, true); c != "" && len(out) < max {
			out = append(out, c)
		}
	}
	return out
}

// providerReviews is bookings' own aggregate over the provider's approved reviews; nil when there are none (never "0 reviews").
func (s *storefrontServiceImpl) providerReviews(ctx context.Context, orgID string) *domain.StorefrontReviews {
	r, err := s.bookings.ProviderRating(ctx, orgID)
	if err != nil {
		return nil
	}
	return reviewsOf(r.Rating, r.RatingCount)
}

// providerLive reports whether a published provider page has something to book (at least one service), for the sitemap.
func (s *storefrontServiceImpl) providerLive(ctx context.Context, orgID string) bool {
	if s.bookings == nil {
		return false
	}
	p, err := s.bookings.Provider(ctx, orgID)
	return err == nil && len(p.Services) > 0
}
