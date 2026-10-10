package app

import (
	"context"
	"fmt"
	"regexp"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ProviderHubService serves the Provider Hub tools inside the portal: calendar with day summaries, booking requests
// and actions, working hours, services, cancellation policy and earnings (bookings, planned), and the public page
// (storefront service; also the seller's "Storefront & theme").
type ProviderHubService interface {
	Calendar(ctx context.Context, c *domain.Caller, from, to string) (domain.CalendarView, error)
	Requests(ctx context.Context, c *domain.Caller) ([]domain.BookingCard, error)
	Act(ctx context.Context, c *domain.Caller, idempotencyKey, bookingID, action, reason string, proposedStart *time.Time) (domain.BookingCard, error)
	Hours(ctx context.Context, c *domain.Caller) (domain.Availability, error)
	SaveHours(ctx context.Context, c *domain.Caller, a domain.Availability) (domain.Availability, error)
	Services(ctx context.Context, c *domain.Caller) ([]domain.ServiceOffer, error)
	CreateService(ctx context.Context, c *domain.Caller, in domain.ServiceInput) (domain.ServiceOffer, error)
	UpdateService(ctx context.Context, c *domain.Caller, id string, in domain.ServiceInput) (domain.ServiceOffer, error)
	Policy(ctx context.Context, c *domain.Caller) (domain.CancellationPolicy, error)
	SavePolicy(ctx context.Context, c *domain.Caller, in domain.PolicyInput) (domain.CancellationPolicy, error)
	Earnings(ctx context.Context, c *domain.Caller, from, to string) (domain.Earnings, error)
	PublicPage(ctx context.Context, c *domain.Caller) (domain.PublicPage, error)
	SavePublicPage(ctx context.Context, c *domain.Caller, in domain.PublicPageInput) (domain.PublicPage, error)
	PublishPublicPage(ctx context.Context, c *domain.Caller) (domain.PublicPage, error)
}

type providerHubImpl struct{ r *orgResolver }

var _ ProviderHubService = (*providerHubImpl)(nil)

// NewProviderHubService returns the ProviderHubService.
func NewProviderHubService(d SellerDeps) ProviderHubService {
	return &providerHubImpl{r: newResolver(d)}
}

const kindProvider = domain.OrgKindProvider

func (p *providerHubImpl) now() time.Time { return p.r.d.Clock.now() }

// dates parses an inclusive date range (at most maxDays long).
func (p *providerHubImpl) dates(from, to string, maxDays int, defaultDays int) (time.Time, time.Time, error) {
	loc := p.r.d.Location
	today := p.now().In(loc)
	start := time.Date(today.Year(), today.Month(), today.Day(), 0, 0, 0, 0, loc)
	end := start.AddDate(0, 0, defaultDays-1)
	if from == "" && to == "" && defaultDays < 0 {
		end = start
		start = start.AddDate(0, 0, defaultDays+1)
	}
	errs := map[string]string{}
	if from != "" {
		t, err := time.ParseInLocation(time.DateOnly, from, loc)
		if err != nil {
			errs["from"] = "Use YYYY-MM-DD"
		}
		start = t
	}
	if to != "" {
		t, err := time.ParseInLocation(time.DateOnly, to, loc)
		if err != nil {
			errs["to"] = "Use YYYY-MM-DD"
		}
		end = t
	}
	if len(errs) == 0 && (end.Before(start) || end.Sub(start) > time.Duration(maxDays)*24*time.Hour) {
		errs["to"] = fmt.Sprintf("Choose a range of up to %d days", maxDays)
	}
	if len(errs) > 0 {
		return start, end, domain.ValidationProblem(errs)
	}
	return start, end, nil
}

func (p *providerHubImpl) normalize(cards []domain.BookingCard) []domain.BookingCard {
	now := p.now()
	out := make([]domain.BookingCard, 0, len(cards))
	for _, b := range cards {
		b.Normalize(now)
		out = append(out, b)
	}
	slices.SortStableFunc(out, func(a, b domain.BookingCard) int { return a.StartAt.Compare(b.StartAt) })
	return out
}

func fullWeek(in []domain.DayHours) []domain.DayHours {
	by := map[string]domain.DayHours{}
	for _, d := range in {
		by[strings.ToUpper(d.Day)] = d
	}
	out := make([]domain.DayHours, 0, 7)
	for _, d := range domain.Weekdays {
		h := by[d]
		h.Day = d
		if h.Ranges == nil {
			h.Ranges = []domain.TimeRange{}
		}
		out = append(out, h)
	}
	return out
}

func (p *providerHubImpl) Calendar(ctx context.Context, c *domain.Caller, from, to string) (domain.CalendarView, error) {
	if from == "" || to == "" {
		return domain.CalendarView{}, domain.ValidationProblem(map[string]string{"from": "from and to are required"})
	}
	start, end, err := p.dates(from, to, 42, 7)
	if err != nil {
		return domain.CalendarView{}, err
	}
	sc, err := p.r.require(ctx, c, kindProvider, "calendar", domain.LevelView)
	if err != nil {
		return domain.CalendarView{}, err
	}
	cal, err := p.r.d.Bookings.Calendar(ctx, c.BearerToken, sc.provider(), start, end)
	if err != nil {
		return domain.CalendarView{}, mapErr("bookings", err)
	}
	tz := firstNonEmpty(cal.Timezone, p.r.d.Location.String())
	loc, lerr := time.LoadLocation(tz)
	if lerr != nil {
		loc = p.r.d.Location
	}
	bookings := p.normalize(cal.Bookings)
	return domain.CalendarView{Timezone: tz, From: start.Format(time.DateOnly), To: end.Format(time.DateOnly), OpenHours: fullWeek(cal.OpenHours),
		Bookings: bookings, Days: domain.DaySummaries(bookings, start, end, loc)}, nil
}

func (p *providerHubImpl) Requests(ctx context.Context, c *domain.Caller) ([]domain.BookingCard, error) {
	sc, err := p.r.require(ctx, c, kindProvider, "requests", domain.LevelView)
	if err != nil {
		return nil, err
	}
	list, err := p.r.d.Bookings.Requests(ctx, c.BearerToken, sc.provider())
	if err != nil {
		return nil, mapErr("bookings", err)
	}
	return p.normalize(list), nil
}

func (p *providerHubImpl) Act(ctx context.Context, c *domain.Caller, key, id, action, reason string, proposed *time.Time) (domain.BookingCard, error) {
	errs := map[string]string{}
	switch {
	case !slices.Contains(domain.BookingActions, action):
		errs["action"] = "Unknown action"
	case (action == "DECLINE" || action == "CANCEL") && strings.TrimSpace(reason) == "":
		errs["reason"] = "Tell the customer why"
	case action == "PROPOSE" && (proposed == nil || !proposed.After(p.now())):
		errs["proposedStartAt"] = "Choose a future time"
	}
	if len([]rune(reason)) > 500 {
		errs["reason"] = "At most 500 characters"
	}
	if len(errs) > 0 {
		return domain.BookingCard{}, domain.ValidationProblem(errs)
	}
	if _, err := p.r.require(ctx, c, kindProvider, "requests", domain.LevelEdit); err != nil {
		return domain.BookingCard{}, err
	}
	b, err := p.r.d.Bookings.Act(ctx, c.BearerToken, key, id, action, strings.TrimSpace(reason), proposed)
	if err != nil {
		return domain.BookingCard{}, mapErr("bookings", err)
	}
	b.Normalize(p.now())
	return b, nil
}

func (p *providerHubImpl) availability(a domain.Availability) domain.Availability {
	a.Weekly = fullWeek(a.Weekly)
	if a.BlockedDates == nil {
		a.BlockedDates = []domain.BlockedDate{}
	}
	if a.Timezone == "" {
		a.Timezone = p.r.d.Location.String()
	}
	return a
}

func (p *providerHubImpl) Hours(ctx context.Context, c *domain.Caller) (domain.Availability, error) {
	sc, err := p.r.require(ctx, c, kindProvider, "hours", domain.LevelView)
	if err != nil {
		return domain.Availability{}, err
	}
	a, err := p.r.d.Bookings.Availability(ctx, c.BearerToken, sc.provider())
	if err != nil {
		return domain.Availability{}, mapErr("bookings", err)
	}
	return p.availability(a), nil
}

func (p *providerHubImpl) SaveHours(ctx context.Context, c *domain.Caller, a domain.Availability) (domain.Availability, error) {
	if errs := a.Validate(); len(errs) > 0 {
		return domain.Availability{}, domain.ValidationProblem(errs)
	}
	sc, err := p.r.require(ctx, c, kindProvider, "hours", domain.LevelEdit)
	if err != nil {
		return domain.Availability{}, err
	}
	out, err := p.r.d.Bookings.SaveAvailability(ctx, c.BearerToken, sc.provider(), p.availability(a))
	if err != nil {
		return domain.Availability{}, mapErr("bookings", err)
	}
	return p.availability(out), nil
}

func serviceOf(s domain.ServiceOffer) domain.ServiceOffer {
	if s.Currency == "" {
		s.Currency = domain.Currency
	}
	return s
}

func (p *providerHubImpl) Services(ctx context.Context, c *domain.Caller) ([]domain.ServiceOffer, error) {
	sc, err := p.r.require(ctx, c, kindProvider, "services", domain.LevelView)
	if err != nil {
		return nil, err
	}
	list, err := p.r.d.Bookings.Services(ctx, c.BearerToken, sc.provider())
	if err != nil {
		return nil, mapErr("bookings", err)
	}
	out := make([]domain.ServiceOffer, 0, len(list))
	for _, s := range list {
		out = append(out, serviceOf(s))
	}
	return out, nil
}

func (p *providerHubImpl) CreateService(ctx context.Context, c *domain.Caller, in domain.ServiceInput) (domain.ServiceOffer, error) {
	if errs := in.Validate(); len(errs) > 0 {
		return domain.ServiceOffer{}, domain.ValidationProblem(errs)
	}
	sc, err := p.r.require(ctx, c, kindProvider, "services", domain.LevelEdit)
	if err != nil {
		return domain.ServiceOffer{}, err
	}
	s, err := p.r.d.Bookings.CreateService(ctx, c.BearerToken, sc.provider(), in)
	if err != nil {
		return domain.ServiceOffer{}, mapErr("bookings", err)
	}
	return serviceOf(s), nil
}

func (p *providerHubImpl) UpdateService(ctx context.Context, c *domain.Caller, id string, in domain.ServiceInput) (domain.ServiceOffer, error) {
	if errs := in.Validate(); len(errs) > 0 {
		return domain.ServiceOffer{}, domain.ValidationProblem(errs)
	}
	sc, err := p.r.require(ctx, c, kindProvider, "services", domain.LevelEdit)
	if err != nil {
		return domain.ServiceOffer{}, err
	}
	s, err := p.r.d.Bookings.UpdateService(ctx, c.BearerToken, sc.provider(), id, in)
	if err != nil {
		return domain.ServiceOffer{}, mapErr("bookings", err)
	}
	return serviceOf(s), nil
}

func (p *providerHubImpl) Policy(ctx context.Context, c *domain.Caller) (domain.CancellationPolicy, error) {
	sc, err := p.r.require(ctx, c, kindProvider, "policy", domain.LevelView)
	if err != nil {
		return domain.CancellationPolicy{}, err
	}
	pol, err := p.r.d.Bookings.Policy(ctx, c.BearerToken, sc.provider())
	if err != nil {
		return domain.CancellationPolicy{}, mapErr("bookings", err)
	}
	pol.Kind = firstNonEmpty(strings.ToUpper(pol.Kind), "MODERATE")
	return pol.Describe(), nil
}

func (p *providerHubImpl) SavePolicy(ctx context.Context, c *domain.Caller, in domain.PolicyInput) (domain.CancellationPolicy, error) {
	if preset, ok := domain.PresetPolicy(in.Kind); ok {
		in = preset
	}
	if errs := in.Validate(); len(errs) > 0 {
		return domain.CancellationPolicy{}, domain.ValidationProblem(errs)
	}
	sc, err := p.r.require(ctx, c, kindProvider, "policy", domain.LevelEdit)
	if err != nil {
		return domain.CancellationPolicy{}, err
	}
	out, err := p.r.d.Bookings.SavePolicy(ctx, c.BearerToken, sc.provider(), in)
	if err != nil {
		return domain.CancellationPolicy{}, mapErr("bookings", err)
	}
	if out.Kind == "" {
		out = in
	}
	return out.Describe(), nil
}

func (p *providerHubImpl) Earnings(ctx context.Context, c *domain.Caller, from, to string) (domain.Earnings, error) {
	start, end, err := p.dates(from, to, 366, -30)
	if err != nil {
		return domain.Earnings{}, err
	}
	sc, err := p.r.require(ctx, c, kindProvider, "earnings", domain.LevelView)
	if err != nil {
		return domain.Earnings{}, err
	}
	var (
		wg      sync.WaitGroup
		e       port.BookingsEarnings
		eErr    error
		fees    []port.ProviderFeeResponse
		feesErr error
	)
	wg.Go(func() { e, eErr = p.r.d.Bookings.Earnings(ctx, c.BearerToken, sc.provider(), start, end) })
	wg.Go(func() { fees, feesErr = p.r.d.Payouts.ProviderFees(ctx, c.BearerToken, sc.org.ID) })
	wg.Wait()
	if eErr != nil {
		return domain.Earnings{}, mapErr("bookings", eErr)
	}
	out := domain.Earnings{Currency: firstNonEmpty(e.Currency, domain.Currency), CommissionPercent: e.CommissionPercent, PayoutDay: "Tuesday",
		Totals: domain.EarningsTotals{HeldMinor: e.HeldMinor}, Rows: e.Rows}
	if out.Rows == nil {
		out.Rows = []domain.EarningsRow{}
	}
	out.Total()
	out.ProviderFees = providerFeesOf(fees, feesErr == nil)
	return out, nil
}

// providerFeesOf sums outstanding and collected provider fees (payments), newest first as payments lists them.
func providerFeesOf(list []port.ProviderFeeResponse, ok bool) domain.ProviderFees {
	out := domain.ProviderFees{Available: ok, Items: []domain.ProviderFee{}}
	for _, f := range list {
		st := strings.ToUpper(f.Status)
		if st != "COLLECTED" {
			st = "OUTSTANDING"
			out.OutstandingMinor += f.AmountMinor
		} else {
			out.CollectedMinor += f.AmountMinor
		}
		out.Items = append(out.Items, domain.ProviderFee{ID: f.ID, BookingReference: firstNonEmpty(f.BookingReference, f.BookingID), AmountMinor: f.AmountMinor,
			Currency: firstNonEmpty(strings.ToUpper(f.Currency), domain.Currency), Reason: firstNonEmpty(f.Reason, "Late cancellation by provider"),
			Status: st, NextAttemptAt: f.NextAttemptAt, CreatedAt: f.CreatedAt})
	}
	return out
}

// ---------------------------------------------------------------- public page (storefront service)

func pageModule(kind string) string {
	if kind == domain.OrgKindProvider {
		return "public-page"
	}
	return "storefront"
}

func str(m map[string]any, k string) string {
	s, _ := m[k].(string)
	return s
}

func obj(m map[string]any, k string) map[string]any {
	o, _ := m[k].(map[string]any)
	if o == nil {
		return map[string]any{}
	}
	return o
}

func list(m map[string]any, k string) []map[string]any {
	raw, _ := m[k].([]any)
	out := []map[string]any{}
	for _, r := range raw {
		if o, ok := r.(map[string]any); ok {
			out = append(out, o)
		}
	}
	return out
}

func pageOf(doc port.Storefront, kind string) domain.PublicPage {
	theme, prov := obj(doc, "theme"), obj(doc, "provider")
	out := domain.PublicPage{Kind: firstNonEmpty(strings.ToUpper(str(doc, "kind")), kind), Slug: str(doc, "slug"), Name: str(doc, "name"),
		Tagline: str(doc, "tagline"), Status: firstNonEmpty(strings.ToUpper(str(doc, "status")), "DRAFT"),
		PrimaryColor: firstNonEmpty(str(theme, "primary"), "#FFD400"), AccentColor: firstNonEmpty(str(theme, "accent"), "#067457"),
		Layout: firstNonEmpty(str(prov, "layout"), "CLASSIC"), Header: firstNonEmpty(str(prov, "header"), "COVER"),
		ButtonShape: firstNonEmpty(str(prov, "buttonShape"), "ROUNDED"), Gallery: []domain.GalleryItem{}, Featured: []domain.FeaturedPost{},
		Social: []domain.SocialLink{}}
	if n, ok := prov["gallerySize"].(float64); ok {
		out.GallerySize = int(n)
	}
	if t, err := time.Parse(time.RFC3339, str(doc, "publishedAt")); err == nil {
		out.PublishedAt = &t
	}
	for _, g := range list(doc, "gallery") {
		out.Gallery = append(out.Gallery, domain.GalleryItem{AssetID: str(g, "assetId"), URL: str(g, "url"), Caption: str(g, "caption")})
	}
	for _, f := range list(doc, "featured") {
		pub, _ := f["published"].(bool)
		out.Featured = append(out.Featured, domain.FeaturedPost{ID: str(f, "id"), Headline: str(f, "headline"), Body: str(f, "body"),
			ServiceID: strPtr(str(f, "serviceId")), Published: pub})
	}
	for _, s := range list(doc, "social") {
		out.Social = append(out.Social, domain.SocialLink{Network: str(s, "network"), URL: str(s, "url"), Status: firstNonEmpty(strings.ToUpper(str(s, "status")), "PENDING")})
	}
	prefix := "/store/"
	if out.Kind == domain.OrgKindProvider {
		prefix = "/pro/"
	}
	out.PublicPath = prefix + out.Slug
	return out
}

var hexColour = regexp.MustCompile(`^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$`)

func validatePage(in domain.PublicPageInput) map[string]string {
	errs := map[string]string{}
	if in.Name != nil {
		if n := len([]rune(strings.TrimSpace(*in.Name))); n < 2 || n > 80 {
			errs["name"] = "2–80 characters"
		}
	}
	if in.Tagline != nil && len([]rune(*in.Tagline)) > 80 {
		errs["tagline"] = "At most 80 characters"
	}
	for k, v := range map[string]*string{"primaryColor": in.PrimaryColor, "accentColor": in.AccentColor} {
		if v != nil && !hexColour.MatchString(*v) {
			errs[k] = "Use a hex colour like #0A8264"
		}
	}
	check := func(field string, v *string, allowed ...string) {
		if v != nil && !slices.Contains(allowed, *v) {
			errs[field] = "Unknown option"
		}
	}
	check("layout", in.Layout, "CLASSIC", "GALLERY_FIRST", "STORY")
	check("header", in.Header, "COVER", "BAND")
	check("buttonShape", in.ButtonShape, "ROUNDED", "SQUARE")
	if in.GallerySize != nil && (*in.GallerySize < 0 || *in.GallerySize > 24) {
		errs["gallerySize"] = "Up to 24 photos (your plan may allow fewer)"
	}
	if len(in.Featured) > 6 {
		errs["featured"] = "Up to 6 featured posts"
	}
	for _, s := range in.Social {
		if !domain.SocialURLMatches(s.Network, s.URL) {
			errs["social."+s.Network] = "Use a https link on " + s.Network
		}
	}
	return errs
}

func applyPage(doc port.Storefront, in domain.PublicPageInput) port.Storefront {
	set := func(m map[string]any, k string, v *string) {
		if v != nil {
			m[k] = strings.TrimSpace(*v)
		}
	}
	set(doc, "name", in.Name)
	set(doc, "tagline", in.Tagline)
	theme, prov := obj(doc, "theme"), obj(doc, "provider")
	set(theme, "primary", in.PrimaryColor)
	set(theme, "accent", in.AccentColor)
	set(prov, "layout", in.Layout)
	set(prov, "header", in.Header)
	set(prov, "buttonShape", in.ButtonShape)
	if in.GallerySize != nil {
		prov["gallerySize"] = *in.GallerySize
	}
	doc["theme"], doc["provider"] = theme, prov
	if in.Featured != nil {
		posts := make([]any, 0, len(in.Featured))
		for _, f := range in.Featured {
			post := map[string]any{"id": f.ID, "headline": f.Headline, "body": f.Body, "published": f.Published}
			if f.ServiceID != nil {
				post["serviceId"] = *f.ServiceID
			}
			posts = append(posts, post)
		}
		doc["featured"] = posts
	}
	if in.Social != nil {
		// Unchanged links keep their approval; new or edited links go back to pending.
		prev := map[string]map[string]any{}
		for _, s := range list(doc, "social") {
			prev[str(s, "network")] = s
		}
		links := make([]any, 0, len(in.Social))
		for _, s := range in.Social {
			status := "PENDING"
			if old, ok := prev[s.Network]; ok && str(old, "url") == s.URL {
				status = firstNonEmpty(str(old, "status"), "PENDING")
			}
			links = append(links, map[string]any{"network": s.Network, "url": s.URL, "status": status})
		}
		doc["social"] = links
	}
	return doc
}

// pageAccess checks the "Storefront & theme" (sellers) or "Public page" (providers) module.
func (p *providerHubImpl) pageAccess(ctx context.Context, c *domain.Caller, level string) (sellerCtx, error) {
	sc, err := p.r.require(ctx, c, "", "", "")
	if err != nil {
		return sc, err
	}
	if !sc.access.Allows(pageModule(sc.kind()), level) {
		return sc, domain.AccessDenied(sc.org.Role, level)
	}
	return sc, nil
}

func (p *providerHubImpl) PublicPage(ctx context.Context, c *domain.Caller) (domain.PublicPage, error) {
	sc, err := p.pageAccess(ctx, c, domain.LevelView)
	if err != nil {
		return domain.PublicPage{}, err
	}
	doc, err := p.r.d.Storefront.Draft(ctx, c.BearerToken)
	if err != nil {
		return domain.PublicPage{}, mapErr("storefront", err)
	}
	return pageOf(doc, sc.kind()), nil
}

func (p *providerHubImpl) SavePublicPage(ctx context.Context, c *domain.Caller, in domain.PublicPageInput) (domain.PublicPage, error) {
	if errs := validatePage(in); len(errs) > 0 {
		return domain.PublicPage{}, domain.ValidationProblem(errs)
	}
	sc, err := p.pageAccess(ctx, c, domain.LevelEdit)
	if err != nil {
		return domain.PublicPage{}, err
	}
	doc, err := p.r.d.Storefront.Draft(ctx, c.BearerToken)
	if err != nil {
		return domain.PublicPage{}, mapErr("storefront", err)
	}
	saved, err := p.r.d.Storefront.SaveDraft(ctx, c.BearerToken, applyPage(doc, in))
	if err != nil {
		return domain.PublicPage{}, mapErr("storefront", err)
	}
	return pageOf(saved, sc.kind()), nil
}

func (p *providerHubImpl) PublishPublicPage(ctx context.Context, c *domain.Caller) (domain.PublicPage, error) {
	sc, err := p.pageAccess(ctx, c, domain.LevelEdit)
	if err != nil {
		return domain.PublicPage{}, err
	}
	if err := p.r.d.Storefront.Publish(ctx, c.BearerToken); err != nil {
		return domain.PublicPage{}, mapErr("storefront", err)
	}
	doc, err := p.r.d.Storefront.Draft(ctx, c.BearerToken)
	if err != nil {
		return domain.PublicPage{}, mapErr("storefront", err)
	}
	page := pageOf(doc, sc.kind())
	if page.Status != "PUBLISHED" {
		page.Status = "PUBLISHED"
		now := p.now().UTC()
		page.PublishedAt = &now
	}
	return page, nil
}
