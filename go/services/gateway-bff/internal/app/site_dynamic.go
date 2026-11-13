package app

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// The dynamic home (version 2): built from what exists. See docs/engineering/dynamic-home.md.
//
//	settings (staff) -> signals snapshot (cached) -> eligibility pass -> pools for shown modules only (cached)
//	-> seeded variety pick per module in page order -> dedupe, seller share, sponsored cap -> blocks

// HomeRequest is everything a home composition depends on besides the catalogue.
type HomeRequest struct {
	Caller  *domain.Caller
	Locale  string
	Visitor string   // X-Visitor-Id (cookie at_vid)
	Window  string   // X-Visit-Window (cookie at_visit)
	Variant string   // X-Home-Variant (Worker cookie)
	Seen    []string // X-Home-Seen: guest novelty memory
	Exclude []string // X-Home-Exclude: ATINs in Recently viewed and the cart (guest history never leaves the request)
	Crawler bool
	// Staff-only (the caller has verified the staff session).
	Debug         bool
	Seed          string
	AsOf          time.Time
	Catalog       string // "", "live", "0", "10", "100", "10000"
	ZIP           string
	SignedIn      *bool
	Deterministic bool
	Preview       bool // nothing is remembered, counted or cached by visitor
}

// Home-wide constants.
const (
	trackedStaffModule = "staff-placement"
	memoryTTL          = 30 * 24 * time.Hour
	seenKeyCap         = 60
	guestSeenCap       = 120
	maxPoolItems       = 24
	dealsFloorProducts = MinRailItems
)

// HomeFor composes the home for the request in the mode the settings give: off (today's composition), shadow (today's
// composition returned, the dynamic one computed and compared) or on.
func (s *siteServiceImpl) HomeFor(ctx context.Context, req HomeRequest) (domain.Home, error) {
	st := s.homeSettings(ctx)
	mode := domain.EffectiveDynamic(st.Dynamic, s.d.Env)
	if req.Preview && mode == domain.DynamicOff {
		mode = domain.DynamicOn
	}
	t0 := time.Now()
	defer func() {
		if s.d.Metrics != nil {
			s.d.Metrics.ComposeSeconds(mode, time.Since(t0).Seconds())
		}
	}()
	switch mode {
	case domain.DynamicOn:
		home, _, err := s.composeV2(ctx, req, st)
		return home, err
	case domain.DynamicShadow:
		legacy, err := s.Home(ctx, req.Caller)
		if err != nil {
			return legacy, err
		}
		shadow := req
		shadow.Preview = true // computed for comparison only: nothing remembered
		if v2, decisions, e := s.composeV2(ctx, shadow, st); e == nil {
			s.compareShadow(ctx, legacy, v2, decisions)
		}
		return legacy, nil
	}
	return s.Home(ctx, req.Caller)
}

// compareShadow counts the modules whose eligibility differs from what the legacy composition showed.
func (s *siteServiceImpl) compareShadow(ctx context.Context, legacy, v2 domain.Home, decisions []domain.ModuleDecision) {
	legacyShown := map[string]bool{domain.ModHero: len(legacy.Hero) > 0}
	for _, b := range legacy.Blocks {
		switch b.Type {
		case domain.BlockDeals:
			legacyShown[domain.ModDeals] = true
		case domain.BlockServices:
			legacyShown[domain.ModServices] = true
		case domain.BlockBasedOnOrders:
			legacyShown[domain.ModContinue] = true
		}
	}
	for _, d := range decisions {
		was, comparable := legacyShown[d.Module]
		if d.Module == domain.ModDeals || d.Module == domain.ModServices || d.Module == domain.ModContinue || d.Module == domain.ModHero {
			comparable = true
		}
		if comparable && was != d.Shown {
			if s.d.Metrics != nil {
				s.d.Metrics.Mismatch(d.Module)
			}
			s.d.Log.InfoContext(ctx, "home shadow mismatch", "module", d.Module, "legacyShown", was, "dynamicShown", d.Shown, "reason", d.Reason)
		}
	}
	_ = v2
}

// ------------------------------------------------------------------ settings, registry, snapshot (shared, cached)

func (s *siteServiceImpl) homeSettings(ctx context.Context) domain.HomeSettings {
	if s.d.HomeContent == nil {
		return domain.DefaultHomeSettings()
	}
	v, _, err := s.settingsCache.get("settings", s.d.Clock.now(), func() (domain.HomeSettings, error) {
		return s.d.HomeContent.HomeSettings(ctx)
	})
	if err != nil {
		s.d.Log.InfoContext(ctx, "home settings unavailable; using the defaults", "error", err)
		return domain.DefaultHomeSettings()
	}
	return v.Merged()
}

func (s *siteServiceImpl) communityResolver(ctx context.Context) domain.CommunityResolver {
	if s.d.HomeContent == nil {
		return domain.NewCommunityResolver(nil)
	}
	v, _, err := s.registryCache.get("registry", s.d.Clock.now(), func() ([]domain.CommunityEntry, error) {
		return s.d.HomeContent.CommunityRegistry(ctx)
	})
	if err != nil {
		s.d.Log.InfoContext(ctx, "community registry unavailable; communities use their raw values", "error", err)
	}
	return domain.NewCommunityResolver(v)
}

func (s *siteServiceImpl) snapshotFor(ctx context.Context, st domain.HomeSettings, res domain.CommunityResolver, src homeSource, catalog string) (homeSnapshot, error) {
	if catalog != "" && catalog != "live" {
		return src.snapshot(ctx, st, res)
	}
	key := fmt.Sprintf("%s|v%d|%d", s.d.Market, st.Version, st.Modules[domain.ModCommunities].UnitMinProduct)
	snap, _, err := s.snapCache.get(key, s.d.Clock.now(), func() (homeSnapshot, error) { return src.snapshot(ctx, st, res) })
	return snap, err
}

// ------------------------------------------------------------------ the visit: seed, window, novelty memory

type visitCtx struct {
	visitor, window string
	seed            uint64
	novelty         map[string]bool
	deterministic   bool
	memKey          string
	prior           port.HomeVisitState
	signedIn        bool
}

func hashKey(s string) string {
	sum := sha256.Sum256([]byte(s))
	return hex.EncodeToString(sum[:10])
}

func cleanID(s string) string {
	s = strings.TrimSpace(s)
	if len(s) > 64 {
		s = s[:64]
	}
	for _, r := range s {
		if r < 0x20 || r > 0x7e {
			return ""
		}
	}
	return s
}

func (s *siteServiceImpl) visit(ctx context.Context, req HomeRequest, st domain.HomeSettings, now time.Time) visitCtx {
	v := visitCtx{novelty: map[string]bool{}}
	if req.Caller.Authenticated() {
		v.signedIn = true
		v.visitor = "u:" + hashKey(req.Caller.Subject)
		v.memKey = v.visitor
	} else {
		v.visitor = cleanID(req.Visitor)
	}
	v.deterministic = req.Crawler || req.Deterministic || v.visitor == ""
	windowMin := max(5, st.Caps.VisitWindowMinutes)
	if v.signedIn && s.d.Memory != nil && !req.Preview {
		if prior, ok, err := s.d.Memory.Load(ctx, v.memKey); err == nil && ok {
			v.prior = prior
		}
	}
	switch {
	case cleanID(req.Window) != "":
		v.window = cleanID(req.Window)
	case v.signedIn && v.prior.Window != "" && now.Sub(v.prior.LastSeen) <= time.Duration(windowMin)*time.Minute:
		v.window = v.prior.Window
	case v.signedIn:
		v.window = hex.EncodeToString([]byte(hashKey(v.visitor + now.UTC().Format(time.RFC3339Nano))))[:16]
	default: // a guest without a visit cookie: the clock is cut into windows, still stable inside one
		v.window = "t" + strconv.FormatInt(now.Unix()/int64(windowMin*60), 36)
	}
	if label, ok := domain.ParseSeedLabel(req.Seed); ok {
		v.seed = label
	} else {
		v.seed = domain.SeedOf(v.visitor, v.window, req.Variant)
	}
	if v.deterministic {
		return v
	}
	if v.signedIn {
		prev := v.prior.Previous
		if v.prior.Window != "" && v.prior.Window != v.window { // the last visit is now history; refreshing inside a visit never counts itself
			prev = append([][]string{v.prior.Current}, prev...)
		}
		for i, keys := range prev {
			if i >= st.Caps.NoveltyLoads {
				break
			}
			for _, k := range keys {
				v.novelty[k] = true
			}
		}
	} else {
		for i, k := range req.Seen {
			if i >= guestSeenCap {
				break
			}
			if k = strings.TrimSpace(k); len(k) == 8 {
				v.novelty[k] = true
			}
		}
	}
	return v
}

func (s *siteServiceImpl) remember(ctx context.Context, v visitCtx, st domain.HomeSettings, now time.Time, seen []string) {
	if !v.signedIn || v.deterministic || s.d.Memory == nil {
		return
	}
	next := port.HomeVisitState{Window: v.window, LastSeen: now, Current: seen, Previous: v.prior.Previous}
	if v.prior.Window != "" && v.prior.Window != v.window {
		next.Previous = append([][]string{v.prior.Current}, v.prior.Previous...)
	}
	if n := max(st.Caps.NoveltyLoads, 1); len(next.Previous) > n {
		next.Previous = next.Previous[:n]
	}
	if err := s.d.Memory.Save(ctx, v.memKey, next, memoryTTL); err != nil {
		s.d.Log.InfoContext(ctx, "home memory not saved", "error", err)
	}
}

// ------------------------------------------------------------------ composition

// built is one automatic module's outcome, kept so decisions and blocks line up.
type built struct {
	key    string
	blocks []domain.HomeBlock
	items  []domain.ProductSummary
}

type composer struct {
	s       *siteServiceImpl
	req     HomeRequest
	st      domain.HomeSettings
	locale  string
	loc     domain.DeliveryLocation
	vis     visitCtx
	src     homeSource
	snap    homeSnapshot
	res     domain.CommunityResolver
	used    map[string]bool
	exclude map[string]bool
	seen    []string
	pools   map[string][]domain.ProductSummary
	near    *[]port.UpBkListing
}

func (s *siteServiceImpl) composeV2(ctx context.Context, req HomeRequest, st domain.HomeSettings) (domain.Home, []domain.ModuleDecision, error) {
	now := s.d.Clock.now()
	if !req.AsOf.IsZero() {
		now = req.AsOf
	}
	st = st.Merged()
	loc := s.d.Location.Current(ctx, req.Caller)
	if req.ZIP != "" {
		loc = domain.DeliveryLocation{ZIP: req.ZIP, Source: domain.LocationDefault}
	}
	c := &composer{s: s, req: req, st: st, locale: normaliseLocale(req.Locale, req.Caller), loc: loc,
		used: map[string]bool{}, exclude: map[string]bool{}, pools: map[string][]domain.ProductSummary{}}
	c.res = s.communityResolver(ctx)
	c.vis = s.visit(ctx, req, st, now)
	for _, a := range req.Exclude {
		if a = strings.TrimSpace(a); a != "" && len(a) <= 40 {
			c.exclude[a] = true
		}
	}
	var src homeSource = &liveSource{s: s}
	if n, err := strconv.Atoi(req.Catalog); err == nil && req.Catalog != "" {
		src = newFixtureSource(n, now)
	}
	c.src = src
	snap, err := s.snapshotFor(ctx, st, c.res, src, req.Catalog)
	if err != nil {
		// No facts, no dynamic page: never guess. The legacy composition still serves.
		s.d.Log.WarnContext(ctx, "home signals unavailable; serving the legacy composition", "error", err)
		legacy, lerr := s.Home(ctx, req.Caller)
		return legacy, nil, lerr
	}
	c.snap = snap
	if s.d.Metrics != nil && !req.Preview {
		s.d.Metrics.SignalsAge(max(0, s.d.Clock.now().Sub(snap.At).Seconds()))
	}

	pl, err := s.placementsFor(ctx, loc.ZIP, req)
	if err != nil {
		s.d.Log.InfoContext(ctx, "placements unavailable; automatic modules only", "error", err)
		pl = domain.Placements{}
	}
	signedIn := req.Caller.Authenticated()
	if req.SignedIn != nil {
		signedIn = *req.SignedIn
	}
	sharedScope := newScope(loc, nil, now)
	if req.Caller != nil && !req.Preview {
		sharedScope = newScope(loc, req.Caller, now)
	}

	// Staff tiles are vetted: a fixed cell needs a real photo and may not point at a community that is not visible.
	visible := visibleCommunities(snap, st.Modules[domain.ModCommunities])
	tiles := c.vetTiles(ctx, pl.Tiles, sharedScope, visible)
	for _, t := range tiles {
		for _, cell := range t.Cells {
			if cell.ATIN != "" {
				c.used[cell.ATIN] = true
			}
		}
	}
	// Staff placements and the personal rails (continue shopping, recently viewed) come first and claim their products.
	pinned := decoratePinned(s.composeBodyAs(ctx, personalCaller(req), signedIn, loc, pl, sharedScope, now, c.used))

	// Signals, and the first eligibility pass (counts only).
	sig := c.signals(pl, pinned, signedIn)
	eng := domain.NewHomeEligibility()
	prev := s.previousShown()
	first := eng.EvaluateWith(sig, st.Modules, prev, st.Caps.Hysteresis)

	// Pools are fetched only for modules the first pass shows (hidden modules cost no upstream call).
	c.fetchPools(ctx, first, now)
	sig = c.refine(sig)
	decisions := eng.EvaluateWith(sig, st.Modules, prev, st.Caps.Hysteresis)

	pinnedKinds := pinnedModuleKinds(pinned)
	order, autos := c.plan(decisions, pinned, pinnedKinds)
	ordered := domain.OrderModules(order, c.vis.seed, c.vis.deterministic)

	blocks, decisions := c.assemble(ctx, ordered, autos, pinned, decisions)
	home := domain.Home{
		Version: 2, GeneratedAt: now.UTC().Format(time.RFC3339), Locale: c.locale, Variant: variantOrControl(req.Variant),
		Announcements: orEmpty(pl.Announcements), Hero: heroSlides(pl.Hero, now), Tiles: orEmpty(tiles), Blocks: orEmpty(blocks),
		Banners: []domain.Banner{}, Rails: []domain.Rail{}, DeliverTo: loc,
		Visit: &domain.HomeVisit{Window: c.vis.window, Seen: orEmpty(c.seen), Deterministic: c.vis.deterministic},
	}
	if req.Debug {
		home.Debug = &domain.HomeDebug{Seed: domain.SeedLabel(c.vis.seed), Visitor: shortVisitor(c.vis.visitor), VisitWindow: c.vis.window,
			NoveltyMemory: len(c.vis.novelty), Dynamic: domain.EffectiveDynamic(st.Dynamic, s.d.Env), Decisions: decisions}
	}
	if !req.Preview {
		s.rememberShown(decisions)
		s.remember(ctx, c.vis, st, now, c.seen)
		if s.d.Metrics != nil {
			for _, d := range decisions {
				s.d.Metrics.Eligibility(d.Module, d.Shown, d.Reason)
			}
		}
	}
	return home, decisions, nil
}

func shortVisitor(v string) string {
	if len(v) > 10 {
		return v[:10]
	}
	return v
}

func variantOrControl(v string) string {
	v = strings.ToLower(strings.TrimSpace(v))
	if v == "" || len(v) > 32 {
		return "control"
	}
	return v
}

// personalCaller is the caller whose own orders feed Continue shopping; a preview never reads a shopper's data.
func personalCaller(req HomeRequest) *domain.Caller {
	if req.Preview {
		return nil
	}
	return req.Caller
}

func normaliseLocale(l string, c *domain.Caller) string {
	cand := strings.ToLower(strings.TrimSpace(l))
	if cand == "" && c != nil {
		cand = strings.ToLower(c.Language)
	}
	for _, p := range strings.Split(cand, ",") {
		p = strings.TrimSpace(strings.SplitN(p, ";", 2)[0])
		if i := strings.IndexAny(p, "-_"); i > 0 {
			p = p[:i]
		}
		for _, k := range domain.Locales {
			if p == k {
				return k
			}
		}
	}
	return "en"
}

func (s *siteServiceImpl) placementsFor(ctx context.Context, zip string, req HomeRequest) (domain.Placements, error) {
	if !req.AsOf.IsZero() && s.d.HomeContent != nil {
		return s.d.HomeContent.PlacementsAt(ctx, s.d.Market, zip, req.AsOf)
	}
	return s.d.Content.Placements(ctx, s.d.Market, zip)
}

// heroSlides keeps the slides inside their window that have a real photo; a slide without one is never shown as a picture.
func heroSlides(in []domain.HeroSlide, now time.Time) []domain.HeroSlide {
	out := []domain.HeroSlide{}
	for _, h := range activeHero(in, now) {
		if realPhoto(h.ImageURL) {
			out = append(out, h)
		}
	}
	return out
}

// vetTiles runs the staff tiles through the same rules as generated ones: rule cells resolve to real products; fixed cells
// need a real photo and a link to a visible community (or a non-community page); a tile left with fewer than two cells goes.
func (c *composer) vetTiles(ctx context.Context, tiles []domain.Tile, sc shopperScope, visible map[string]bool) []domain.Tile {
	for i := range tiles {
		if len(tiles[i].Cells) > 4 {
			tiles[i].Cells = tiles[i].Cells[:4]
		}
	}
	resolved := c.s.resolveTiles(ctx, tiles, sc)
	out := make([]domain.Tile, 0, len(resolved))
	for _, t := range resolved {
		cells := make([]domain.TileCell, 0, len(t.Cells))
		for _, cell := range t.Cells {
			if !realPhoto(cell.ImageURL) {
				continue
			}
			if rest, ok := strings.CutPrefix(cell.Href, "/communities/"); ok {
				key, _ := c.res.Resolve(strings.SplitN(rest, "?", 2)[0])
				if !visible[key] {
					continue
				}
			}
			cells = append(cells, cell)
		}
		if len(cells) >= minRuleCells {
			t.Cells = cells
			out = append(out, t)
		}
	}
	return out
}

// visibleCommunities are the community keys above the staff thresholds (nil when fewer than the module needs).
func visibleCommunities(snap homeSnapshot, r domain.ModuleRule) map[string]bool {
	out := map[string]bool{}
	var ok []snapComm
	for _, cm := range snap.Communities {
		if cm.Count >= r.UnitMinProduct && cm.Sellers >= r.UnitMinSellers {
			ok = append(ok, cm)
		}
	}
	if len(ok) < r.MinUnits {
		return out
	}
	for _, cm := range ok {
		out[cm.Key] = true
	}
	return out
}

func decoratePinned(in []domain.HomeBlock) []domain.HomeBlock {
	for i := range in {
		b := &in[i]
		switch b.Type {
		case domain.BlockBasedOnOrders:
			b.Title, b.TitleKey, b.Module, b.Scope = "", domain.KeyContinueTitle, domain.ModContinue, domain.ScopeVisitor
		case domain.BlockRecentlyViewed:
			b.Title, b.TitleKey, b.Module, b.Scope = "", domain.KeyRecentTitle, domain.ModRecent, domain.ScopeVisitor
		}
	}
	return in
}

// pinnedModuleKinds are the automatic modules a staff placement already covers (the placement wins the slot).
func pinnedModuleKinds(blocks []domain.HomeBlock) map[string]string {
	out := map[string]string{}
	for _, b := range blocks {
		switch b.Type {
		case domain.BlockDeals:
			out[domain.ModDeals] = b.Title
		case domain.BlockServices:
			out[domain.ModServices] = b.Title
		case domain.BlockPreorders:
			out[domain.ModPreorder] = b.Title
		case domain.BlockMembership:
			out[domain.ModPlus] = b.Title
		}
	}
	return out
}

// signals fills the eligibility input from the snapshot, the placements and what staff pinned.
func (c *composer) signals(pl domain.Placements, pinned []domain.HomeBlock, signedIn bool) domain.HomeSignals {
	snap := c.snap
	sig := domain.HomeSignals{EligibleProducts: snap.Total, Sellers: snap.Sellers, SignedIn: signedIn, ServiceProvidersAll: snap.Providers,
		HeroSlides: len(heroSlides(pl.Hero, c.s.d.Clock.now())), DealProducts: snap.Total, NewProducts: snap.Total, TopSellerProducts: snap.Total}
	if !c.req.AsOf.IsZero() {
		sig.HeroSlides = len(heroSlides(pl.Hero, c.req.AsOf))
	}
	for _, d := range snap.Departments {
		sig.Departments = append(sig.Departments, domain.UnitSignal{Name: d.Name, Products: d.Count, Sellers: d.Sellers})
		if d.Key == "groceries-food" {
			sig.GroceryProducts, sig.GroceryDepartment = d.Count, d.Name
		}
	}
	for _, cm := range snap.Communities {
		sig.Communities = append(sig.Communities, domain.UnitSignal{Name: cm.Key, Products: cm.Count, Sellers: cm.Sellers})
	}
	for _, b := range pinned {
		switch b.Type {
		case domain.BlockBasedOnOrders:
			sig.OrdersBought = max(1, railLen(b))
		case domain.BlockRecentlyViewed:
			sig.RecentlyViewed = 1 // the browser's own history decides; the slot is reserved and hides itself when empty
		case domain.BlockPreorders:
			sig.PreorderWindows++
		case domain.BlockMembership:
			sig.PlusEnabled = true
		}
	}
	c.nearProviders(&sig)
	return sig
}

func railLen(b domain.HomeBlock) int {
	if b.Rail == nil {
		return 0
	}
	return len(b.Rail.Items)
}

// PreviewHome composes with staff-chosen inputs; nothing is remembered or counted.
func (s *siteServiceImpl) PreviewHome(ctx context.Context, req HomeRequest) (domain.Home, []domain.ModuleDecision, error) {
	req.Preview = true
	return s.composeV2(ctx, req, s.homeSettings(ctx))
}

// communityCounts exposes the shared snapshot to the staff community screen.
func (s *siteServiceImpl) communityCounts(ctx context.Context) (homeSnapshot, domain.HomeSettings, domain.CommunityResolver, error) {
	return s.navSnapshot(ctx)
}
