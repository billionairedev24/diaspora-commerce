package app

import (
	"context"
	"fmt"
	"net/url"
	"sort"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Navigation, departments, communities and the sitemap source (S419, S420). Every list is generated from the live
// taxonomy and the eligible-only counts of the one snapshot the home uses; nothing here names a department or community.

func (s *siteServiceImpl) navSnapshot(ctx context.Context) (homeSnapshot, domain.HomeSettings, domain.CommunityResolver, error) {
	st := s.homeSettings(ctx)
	res := s.communityResolver(ctx)
	snap, err := s.snapshotFor(ctx, st, res, &liveSource{s: s}, "")
	if err != nil {
		return snap, st, res, fmt.Errorf("signals: %w", mapErr("search", err))
	}
	publishDepartments(snap)
	return snap, st, res, nil
}

func (s *siteServiceImpl) siteDepartments(snap homeSnapshot, st domain.HomeSettings, locale string, includeEmpty bool) []domain.SiteDepartment {
	out := make([]domain.SiteDepartment, 0, len(snap.Departments))
	for _, d := range snap.Departments {
		if d.Count <= 0 && !includeEmpty {
			continue
		}
		labels := domain.DepartmentLabels(d.Name, st.DepartmentLabels)
		sd := domain.SiteDepartment{Key: d.Key, Name: d.Name, Label: labels.For(locale, d.Name), Labels: labels, Count: d.Count, Sellers: d.Sellers, ImageURL: nil}
		if realPhoto(d.Image) {
			sd.ImageURL = d.Image
		}
		if _, ok := deptLabelKey(d.Key); ok {
			sd.LabelKey = "dept." + d.Key
		}
		if d.Count > 0 { // a department with nothing in it is not linked
			sd.Href = "/s?dept=" + url.QueryEscape(d.Name)
		}
		out = append(out, sd)
	}
	return out
}

func (s *siteServiceImpl) siteCommunities(snap homeSnapshot, st domain.HomeSettings, res domain.CommunityResolver, locale string) []domain.SiteCommunity {
	rule := st.Modules[domain.ModCommunities]
	if !rule.Enabled {
		return []domain.SiteCommunity{}
	}
	var ok []snapComm
	for _, cm := range snap.Communities {
		if cm.Count >= rule.UnitMinProduct && cm.Sellers >= rule.UnitMinSellers {
			ok = append(ok, cm)
		}
	}
	if len(ok) < rule.MinUnits {
		return []domain.SiteCommunity{}
	}
	out := make([]domain.SiteCommunity, 0, len(ok))
	for _, cm := range ok {
		labels := res.Labels(cm.Key)
		sc := domain.SiteCommunity{Key: cm.Key, Label: labels.For(locale, cm.Key), Labels: labels, Count: cm.Count, Sellers: cm.Sellers,
			Href: "/communities/" + url.PathEscape(cm.Key), ImageSource: "none"}
		if _, e := res.Resolve(cm.Key); e != nil && realPhoto(e.ImageURL) {
			sc.ImageURL, sc.ImageSource = e.ImageURL, "editorial"
		} else if realPhoto(cm.Image) {
			sc.ImageURL, sc.ImageSource = cm.Image, "product"
		}
		out = append(out, sc)
	}
	return out
}

func (s *siteServiceImpl) Navigation(ctx context.Context, locale string) (domain.SiteNavigation, error) {
	snap, st, res, err := s.navSnapshot(ctx)
	if err != nil {
		return domain.SiteNavigation{}, err
	}
	locale = normaliseLocale(locale, nil)
	all := s.siteDepartments(snap, st, locale, false)
	bar := append([]domain.SiteDepartment{}, all...)
	if n := max(st.Caps.NavBarSize, 1); len(bar) > n {
		bar = bar[:n]
	}
	deals := st.Modules[domain.ModDeals]
	nav := domain.SiteNavigation{Bar: bar, All: all, Communities: s.siteCommunities(snap, st, res, locale),
		Services:    domain.SiteServices{Available: snap.Providers > 0 && st.Modules[domain.ModServices].Enabled, Providers: snap.Providers, Href: "/services"},
		Deals:       domain.NavAvailable{Available: deals.Enabled && snap.Total >= deals.MinProducts && s.dealsAvailable(ctx, st, snap)},
		Brand:       domain.NavAvailable{Available: snap.Brand > 0},
		GeneratedAt: snap.At.UTC().Format(time.RFC3339)}
	return nav, nil
}

// dealsAvailable is whether the Deals module would be shown: the shared deals pool holds enough discounted products.
func (s *siteServiceImpl) dealsAvailable(ctx context.Context, st domain.HomeSettings, snap homeSnapshot) bool {
	rule := st.Modules[domain.ModDeals]
	spec := poolSpec{Module: domain.ModDeals, Sort: domain.SortRating, Limit: min(maxPoolItems, max(rule.Show, 1)*max(st.Caps.PoolMultiplier, 1))}
	key := fmt.Sprintf("%s||%s|%d", spec.Module, spec.Sort, spec.Limit)
	pool, _, err := s.poolCache.get(key, s.d.Clock.now(), func() ([]domain.ProductSummary, error) { return (&liveSource{s: s}).pool(ctx, spec) })
	if err != nil {
		return false
	}
	deals := dealsFromMin(pool, maxPoolItems, st.Caps.DealMinPercent)
	return len(deals) >= rule.MinProducts && (rule.MinSellers == 0 || distinctSellerKeys(deals) >= rule.MinSellers)
}

func (s *siteServiceImpl) Departments(ctx context.Context, locale string, includeEmpty bool) ([]domain.SiteDepartment, time.Time, error) {
	snap, st, _, err := s.navSnapshot(ctx)
	if err != nil {
		return nil, time.Time{}, err
	}
	return s.siteDepartments(snap, st, normaliseLocale(locale, nil), includeEmpty), snap.At, nil
}

func (s *siteServiceImpl) Communities(ctx context.Context, locale string) ([]domain.SiteCommunity, time.Time, error) {
	snap, st, res, err := s.navSnapshot(ctx)
	if err != nil {
		return nil, time.Time{}, err
	}
	return s.siteCommunities(snap, st, res, normaliseLocale(locale, nil)), snap.At, nil
}

// Community resolves /communities/{slug}: canonical is false for an alias or case variant (the storefront redirects to
// the canonical key); a community below the thresholds is port.ErrNotFound (a 404, never a zero-result page).
func (s *siteServiceImpl) Community(ctx context.Context, slug, locale string) (domain.SiteCommunity, bool, error) {
	snap, st, res, err := s.navSnapshot(ctx)
	if err != nil {
		return domain.SiteCommunity{}, false, err
	}
	key, _ := res.Resolve(slug)
	for _, c := range s.siteCommunities(snap, st, res, normaliseLocale(locale, nil)) {
		if c.Key == key {
			return c, strings.TrimSpace(slug) == key, nil
		}
	}
	return domain.SiteCommunity{}, false, port.ErrNotFound
}

// SitemapSources lists the department and community URLs with eligible products only, from the same snapshot.
func (s *siteServiceImpl) SitemapSources(ctx context.Context) (domain.SitemapSources, error) {
	snap, st, res, err := s.navSnapshot(ctx)
	if err != nil {
		return domain.SitemapSources{}, err
	}
	out := domain.SitemapSources{Departments: []domain.SitemapEntry{}, Communities: []domain.SitemapEntry{}}
	lastmod := snap.At.UTC().Format(time.RFC3339)
	for _, d := range s.siteDepartments(snap, st, "en", false) {
		out.Departments = append(out.Departments, domain.SitemapEntry{Href: d.Href, Count: d.Count, LastMod: lastmod})
	}
	for _, c := range s.siteCommunities(snap, st, res, "en") {
		out.Communities = append(out.Communities, domain.SitemapEntry{Href: c.Href, Count: c.Count, LastMod: lastmod})
	}
	sort.SliceStable(out.Departments, func(i, j int) bool { return out.Departments[i].Href < out.Departments[j].Href })
	sort.SliceStable(out.Communities, func(i, j int) bool { return out.Communities[i].Href < out.Communities[j].Href })
	return out, nil
}

var homeEvents = map[string]bool{"impression": true, "click": true}
var homeTargets = map[string]bool{"": true, "product": true, "department": true, "community": true, "service": true, "other": true}

// RecordModuleEvent validates and counts a module event. Unknown module keys are rejected (this is what keeps the metric
// label set small and fixed); nothing identifies the shopper.
func (s *siteServiceImpl) RecordModuleEvent(_ context.Context, ev domain.HomeModuleEvent) error {
	bad := map[string]string{}
	if !domain.IsModule(ev.Module) && ev.Module != trackedStaffModule {
		bad["module"] = "Unknown module"
	}
	if !homeEvents[ev.Event] {
		bad["event"] = "Must be impression or click"
	}
	if !homeTargets[ev.Target] {
		bad["target"] = "Unknown target"
	}
	if ev.Position < 0 || ev.Position > 30 {
		bad["position"] = "Must be between 0 and 30"
	}
	if len(bad) > 0 {
		return domain.ValidationProblem(bad)
	}
	if s.d.Metrics != nil {
		s.d.Metrics.ModuleEvent(ev.Module, ev.Event, variantOrControl(ev.Variant), normaliseLocale(ev.Locale, nil))
	}
	return nil
}
