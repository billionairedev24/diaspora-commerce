package app

import (
	"context"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Marketing > Home (S417, S418, S430): the staff-editable home settings, the community registry and the preview with
// the reason every module is shown or hidden. Settings and registry live in content; the gateway relays them with the
// staff member's own token, validates first, and adds what only it knows (the effective mode, the module names).

const homeModule = "marketing"

// StaffHomeSettings is the settings document with the mode the page runs now and the names staff read for modules.
type StaffHomeSettings struct {
	domain.HomeSettings
	EffectiveDynamic string            `json:"effectiveDynamic"`
	ModuleNames      map[string]string `json:"moduleNames"`
}

// StaffHomeSave is the body of a settings save.
type StaffHomeSave struct {
	ExpectedVersion  int                            `json:"expectedVersion"`
	Dynamic          *string                        `json:"dynamic"`
	Modules          map[string]domain.ModuleRule   `json:"modules"`
	Caps             domain.HomeCaps                `json:"caps"`
	DepartmentLabels map[string]domain.LocaleLabels `json:"departmentLabels"`
	Reason           string                         `json:"reason"`
}

// StaffHomePreviewQuery chooses what the preview shows.
type StaffHomePreviewQuery struct {
	ZIP           string
	SignedIn      bool
	Locale        string
	AsOf          time.Time
	Catalog       string
	Seed          string
	Deterministic bool
	Variant       string
}

// StaffHomePreview is the page as shoppers see it plus every module's decision.
type StaffHomePreview struct {
	AsOf      string                  `json:"asOf"`
	Locale    string                  `json:"locale"`
	Catalog   string                  `json:"catalog"`
	Seed      string                  `json:"seed"`
	DeliverTo map[string]string       `json:"deliverTo,omitempty"`
	Blocks    []domain.HomeBlock      `json:"blocks"`
	Hero      []domain.HeroSlide      `json:"hero"`
	Tiles     []domain.Tile           `json:"tiles"`
	Decisions []domain.ModuleDecision `json:"decisions"`
	Season    *StaffHomeSeason        `json:"season"`
}

// StaffHomeSeason is the scheduled campaign window active at the previewed instant.
type StaffHomeSeason struct {
	Name     string   `json:"name"`
	StartsAt string   `json:"startsAt,omitempty"`
	EndsAt   string   `json:"endsAt,omitempty"`
	Boosted  []string `json:"boosted"`
}

// StaffHomeService is the Marketing > Home use case.
type StaffHomeService interface {
	// CanView says whether the caller is staff who may open Marketing (used to allow ?debug=1 on the public home).
	CanView(ctx context.Context, c *domain.Caller) bool
	Settings(ctx context.Context, c *domain.Caller) (StaffHomeSettings, error)
	SaveSettings(ctx context.Context, c *domain.Caller, in StaffHomeSave) (StaffHomeSettings, error)
	Versions(ctx context.Context, c *domain.Caller) (any, error)
	Revert(ctx context.Context, c *domain.Caller, toVersion int, reason string) (StaffHomeSettings, error)
	Communities(ctx context.Context, c *domain.Caller) (any, error)
	PutCommunity(ctx context.Context, c *domain.Caller, key string, body map[string]any) (any, error)
	DeleteCommunity(ctx context.Context, c *domain.Caller, key, reason string) error
	Preview(ctx context.Context, c *domain.Caller, q StaffHomePreviewQuery) (StaffHomePreview, error)
}

type staffHomeImpl struct {
	r    *staffResolver
	site SiteService
	env  string
}

var _ StaffHomeService = (*staffHomeImpl)(nil)

// NewStaffHomeService returns the Marketing > Home use case. env is APP_ENV (the default of home.dynamic).
func NewStaffHomeService(d StaffDeps, site SiteService, env string) StaffHomeService {
	return &staffHomeImpl{r: newStaffResolver(d), site: site, env: env}
}

func (s *staffHomeImpl) CanView(ctx context.Context, c *domain.Caller) bool {
	if !c.Authenticated() {
		return false
	}
	_, err := s.r.require(ctx, c, homeModule, domain.LevelView)
	return err == nil
}

func (s *staffHomeImpl) need(ctx context.Context, c *domain.Caller, level string) (staffCtx, error) {
	sc, err := s.r.require(ctx, c, homeModule, level)
	if err != nil {
		return sc, err
	}
	if sc.up.Content == nil {
		return sc, domain.UpstreamUnavailable("content")
	}
	return sc, nil
}

func (s *staffHomeImpl) wrap(h domain.HomeSettings) StaffHomeSettings {
	h = h.Merged()
	return StaffHomeSettings{HomeSettings: h, EffectiveDynamic: domain.EffectiveDynamic(h.Dynamic, s.env), ModuleNames: domain.ModuleLabels()}
}

func (s *staffHomeImpl) Settings(ctx context.Context, c *domain.Caller) (StaffHomeSettings, error) {
	sc, err := s.need(ctx, c, domain.LevelView)
	if err != nil {
		return StaffHomeSettings{}, err
	}
	var h domain.HomeSettings
	if err := sc.get(ctx, sc.up.Content, "/v1/home-settings", nil, &h); err != nil {
		return StaffHomeSettings{}, err
	}
	return s.wrap(h), nil
}

func validReason(reason string) map[string]string {
	if n := len([]rune(strings.TrimSpace(reason))); n < 3 || n > 300 {
		return map[string]string{"reason": "Say why you are changing this (3 to 300 characters)"}
	}
	return nil
}

func (s *staffHomeImpl) SaveSettings(ctx context.Context, c *domain.Caller, in StaffHomeSave) (StaffHomeSettings, error) {
	sc, err := s.need(ctx, c, domain.LevelEdit)
	if err != nil {
		return StaffHomeSettings{}, err
	}
	next := domain.HomeSettings{Dynamic: in.Dynamic, Modules: in.Modules, Caps: in.Caps, DepartmentLabels: in.DepartmentLabels}
	bad := validReason(in.Reason)
	if bad == nil {
		bad = map[string]string{}
	}
	for k, v := range domain.ValidateHomeSettings(next) {
		bad[k] = v
	}
	if in.ExpectedVersion < 0 {
		bad["expectedVersion"] = "Cannot be negative"
	}
	if len(bad) > 0 {
		return StaffHomeSettings{}, domain.ValidationProblem(bad)
	}
	body := map[string]any{"expectedVersion": in.ExpectedVersion, "reason": strings.TrimSpace(in.Reason),
		"settings": map[string]any{"dynamic": in.Dynamic, "modules": in.Modules, "caps": in.Caps, "departmentLabels": in.DepartmentLabels}}
	var h domain.HomeSettings
	if err := sc.call(ctx, sc.up.Content, port.StaffCall{Method: http.MethodPut, Path: "/v1/home-settings", Body: body}, &h); err != nil {
		return StaffHomeSettings{}, err
	}
	return s.wrap(h), nil
}

func (s *staffHomeImpl) Versions(ctx context.Context, c *domain.Caller) (any, error) {
	sc, err := s.need(ctx, c, domain.LevelView)
	if err != nil {
		return nil, err
	}
	var out []any
	if err := sc.get(ctx, sc.up.Content, "/v1/home-settings/versions", nil, &out); err != nil {
		return nil, err
	}
	if out == nil {
		out = []any{}
	}
	return out, nil
}

func (s *staffHomeImpl) Revert(ctx context.Context, c *domain.Caller, toVersion int, reason string) (StaffHomeSettings, error) {
	sc, err := s.need(ctx, c, domain.LevelEdit)
	if err != nil {
		return StaffHomeSettings{}, err
	}
	bad := validReason(reason)
	if toVersion < 1 {
		if bad == nil {
			bad = map[string]string{}
		}
		bad["toVersion"] = "Pick an earlier version"
	}
	if bad != nil {
		return StaffHomeSettings{}, domain.ValidationProblem(bad)
	}
	var h domain.HomeSettings
	if err := sc.post(ctx, sc.up.Content, "/v1/home-settings/revert", map[string]any{"toVersion": toVersion, "reason": strings.TrimSpace(reason)}, &h); err != nil {
		return StaffHomeSettings{}, err
	}
	return s.wrap(h), nil
}

// Communities returns the registry as staff see it (with each entry's live eligible count) plus the raw product values
// that map to no entry yet, so staff can merge "nigerian" and "Nigerian" by adding an alias.
func (s *staffHomeImpl) Communities(ctx context.Context, c *domain.Caller) (any, error) {
	sc, err := s.need(ctx, c, domain.LevelView)
	if err != nil {
		return nil, err
	}
	var entries []domain.CommunityEntry
	if err := sc.get(ctx, sc.up.Content, "/v1/community-registry", nil, &entries); err != nil {
		return nil, err
	}
	type entryOut struct {
		domain.CommunityEntry
		ImageAccepted    bool `json:"imageAccepted"`
		EligibleProducts int  `json:"eligibleProducts"`
		Sellers          int  `json:"sellers"`
		Visible          bool `json:"visible"`
	}
	type rawOut struct {
		Value            string `json:"value"`
		EligibleProducts int    `json:"eligibleProducts"`
	}
	registered, unregistered := []entryOut{}, []rawOut{}
	snapper, ok := s.site.(interface {
		communityCounts(ctx context.Context) (homeSnapshot, domain.HomeSettings, domain.CommunityResolver, error)
	})
	var snap homeSnapshot
	var st domain.HomeSettings
	res := domain.NewCommunityResolver(entries)
	if ok {
		if sn, set, _, err := snapper.communityCounts(ctx); err == nil {
			snap, st = sn, set
		}
	}
	rule := st.Modules[domain.ModCommunities]
	visible := visibleCommunities(snap, rule)
	counts := map[string]snapComm{}
	for _, cm := range snap.Communities {
		counts[cm.Key] = cm
	}
	for _, e := range entries {
		o := entryOut{CommunityEntry: e, ImageAccepted: e.ImageURL != nil && realPhoto(e.ImageURL)}
		if cm, ok := counts[e.Key]; ok {
			o.EligibleProducts, o.Sellers = cm.Count, cm.Sellers
		}
		o.Visible = visible[e.Key]
		registered = append(registered, o)
	}
	for _, cm := range snap.Communities {
		for _, raw := range cm.Raw {
			if _, e := res.Resolve(raw); e == nil {
				unregistered = append(unregistered, rawOut{Value: raw, EligibleProducts: cm.Count})
			}
		}
	}
	sort.SliceStable(unregistered, func(i, j int) bool { return unregistered[i].EligibleProducts > unregistered[j].EligibleProducts })
	return map[string]any{"registered": registered, "unregistered": unregistered}, nil
}

func (s *staffHomeImpl) PutCommunity(ctx context.Context, c *domain.Caller, key string, body map[string]any) (any, error) {
	sc, err := s.need(ctx, c, domain.LevelEdit)
	if err != nil {
		return nil, err
	}
	reason, _ := body["reason"].(string)
	bad := validReason(reason)
	if bad == nil {
		bad = map[string]string{}
	}
	if labels, _ := body["labels"].(map[string]any); labels == nil || strings.TrimSpace(asString(labels["en"])) == "" {
		bad["labels.en"] = "An English label is required"
	}
	if img, ok := body["imageUrl"].(string); ok && img != "" && !strings.HasPrefix(img, "https://") {
		bad["imageUrl"] = "Must be an https address"
	}
	if len(bad) > 0 {
		return nil, domain.ValidationProblem(bad)
	}
	var out jobj
	if err := sc.call(ctx, sc.up.Content, port.StaffCall{Method: http.MethodPut, Path: "/v1/community-registry/" + url.PathEscape(key), Body: body}, &out); err != nil {
		return nil, err
	}
	return out, nil
}

func asString(v any) string { s, _ := v.(string); return s }

func (s *staffHomeImpl) DeleteCommunity(ctx context.Context, c *domain.Caller, key, reason string) error {
	sc, err := s.need(ctx, c, domain.LevelEdit)
	if err != nil {
		return err
	}
	if bad := validReason(reason); bad != nil {
		return domain.ValidationProblem(bad)
	}
	return sc.call(ctx, sc.up.Content, port.StaffCall{Method: http.MethodDelete, Path: "/v1/community-registry/" + url.PathEscape(key), Query: url.Values{"reason": {strings.TrimSpace(reason)}}}, nil)
}

var previewCatalogs = map[string]bool{"": true, "live": true, "0": true, "10": true, "100": true, "10000": true}

func (s *staffHomeImpl) Preview(ctx context.Context, c *domain.Caller, q StaffHomePreviewQuery) (StaffHomePreview, error) {
	if _, err := s.need(ctx, c, domain.LevelView); err != nil {
		return StaffHomePreview{}, err
	}
	bad := map[string]string{}
	if q.ZIP != "" && !zip5Home(q.ZIP) {
		bad["zip"] = "Enter a 5-digit ZIP"
	}
	if !previewCatalogs[q.Catalog] {
		bad["catalog"] = "Must be live, 0, 10, 100 or 10000"
	}
	if len(bad) > 0 {
		return StaffHomePreview{}, domain.ValidationProblem(bad)
	}
	asOf := q.AsOf
	if asOf.IsZero() {
		asOf = time.Now()
	}
	signed := q.SignedIn
	req := HomeRequest{Locale: q.Locale, AsOf: asOf, Catalog: q.Catalog, ZIP: q.ZIP, SignedIn: &signed, Seed: q.Seed, Deterministic: q.Deterministic || q.Seed == "",
		Variant: q.Variant, Debug: true, Preview: true}
	if q.Seed != "" {
		req.Deterministic = q.Deterministic
		req.Visitor = "preview"
	}
	home, decisions, err := s.site.PreviewHome(ctx, req)
	if err != nil {
		return StaffHomePreview{}, err
	}
	out := StaffHomePreview{AsOf: asOf.UTC().Format(time.RFC3339), Locale: home.Locale, Catalog: firstNonEmpty(q.Catalog, "live"), Blocks: orEmpty(home.Blocks),
		Hero: orEmpty(home.Hero), Tiles: orEmpty(home.Tiles), Decisions: decisions}
	if home.Debug != nil {
		out.Seed = home.Debug.Seed
	}
	if out.Decisions == nil {
		out.Decisions = []domain.ModuleDecision{}
	}
	if home.DeliverTo.ZIP != "" {
		out.DeliverTo = map[string]string{"zip": home.DeliverTo.ZIP, "city": home.DeliverTo.City, "state": home.DeliverTo.State}
	}
	for _, h := range home.Hero {
		if h.StartsAt != nil || h.EndsAt != nil {
			out.Season = &StaffHomeSeason{Name: firstNonEmpty(h.Kicker, h.Title), Boosted: []string{domain.ModHero}}
			if h.StartsAt != nil {
				out.Season.StartsAt = *h.StartsAt
			}
			if h.EndsAt != nil {
				out.Season.EndsAt = *h.EndsAt
			}
			break
		}
	}
	return out, nil
}
