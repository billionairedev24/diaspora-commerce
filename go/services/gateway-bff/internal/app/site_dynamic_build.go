package app

import (
	"context"
	"fmt"
	"net/url"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// poolModules are the automatic modules that draw a product pool (everything else reads the snapshot).
var poolModules = map[string]bool{domain.ModDeals: true, domain.ModNewArrivals: true, domain.ModTopSellers: true, domain.ModGroceries: true, domain.ModDeptRail: true}

// deptRailCount is how many "Top in {department}" rails the page can carry.
const deptRailCount = 2

func (c *composer) poolSize(rule domain.ModuleRule) int {
	return min(maxPoolItems, max(rule.Show, 1)*max(c.st.Caps.PoolMultiplier, 1))
}

func poolKey(module, dept string) string { return module + "|" + dept }

func (c *composer) specFor(module, dept string, rule domain.ModuleRule) poolSpec {
	spec := poolSpec{Module: module, Department: dept, Sort: domain.SortRating, Limit: c.poolSize(rule)}
	if module == domain.ModNewArrivals {
		spec.Sort = domain.SortNewest
	}
	return spec
}

// railDepartments are the departments that get a "Top in" rail: the biggest few that qualify, rotated by the seed among
// the top four so the rails differ between visits.
func (c *composer) railDepartments(units []string) []string {
	if len(units) > 4 {
		units = units[:4]
	}
	items := make([]domain.VarietyItem, len(units))
	for i, u := range units {
		items[i] = domain.VarietyItem{Key: u, Quality: 1 - float64(i)*0.1}
	}
	picked := domain.PickVariety(items, domain.VarietyParams{Seed: c.vis.seed, Module: domain.ModDeptRail, Show: deptRailCount, Deterministic: c.vis.deterministic})
	out := make([]string, len(picked))
	for i, p := range picked {
		out[i] = p.Key
	}
	return out
}

// fetchPools loads the candidate pools of the modules the first pass shows, in parallel. A pool is shared and cached for
// 60 s (fresh) and 300 s (stale on failure), keyed by what it is, never by shopper.
func (c *composer) fetchPools(ctx context.Context, first []domain.ModuleDecision, now time.Time) {
	var want []poolSpec
	for _, d := range first {
		if !d.Shown || !poolModules[d.Module] {
			continue
		}
		rule := c.st.Modules[d.Module]
		switch d.Module {
		case domain.ModGroceries:
			want = append(want, c.specFor(d.Module, c.groceryDept(), rule))
		case domain.ModDeptRail:
			for _, dept := range c.railDepartments(d.Units) {
				want = append(want, c.specFor(d.Module, dept, rule))
			}
		default:
			want = append(want, c.specFor(d.Module, "", rule))
		}
	}
	var mu sync.Mutex
	var wg sync.WaitGroup
	for _, spec := range want {
		wg.Go(func() {
			load := func() ([]domain.ProductSummary, error) { return c.src.pool(ctx, spec) }
			var items []domain.ProductSummary
			var err error
			if _, fixture := c.src.(*fixtureSource); fixture {
				items, err = load()
			} else {
				key := fmt.Sprintf("%s|%s|%s|%d", spec.Module, spec.Department, spec.Sort, spec.Limit)
				items, _, err = c.s.poolCache.get(key, now, load)
			}
			if err != nil {
				c.s.d.Log.InfoContext(ctx, "home pool unavailable; module hidden", "module", spec.Module, "error", err)
				return
			}
			mu.Lock()
			c.pools[poolKey(spec.Module, spec.Department)] = items
			mu.Unlock()
		})
	}
	wg.Wait()
}

func (c *composer) groceryDept() string {
	for _, d := range c.snap.Departments {
		if d.Key == "groceries-food" {
			return d.Name
		}
	}
	return ""
}

// refine replaces the upper-bound counts of the pool modules with what their pools really hold.
func (c *composer) refine(sig domain.HomeSignals) domain.HomeSignals {
	if p, ok := c.pools[poolKey(domain.ModDeals, "")]; ok {
		sig.DealProducts = len(dealsFromMin(p, maxPoolItems, c.st.Caps.DealMinPercent))
	}
	if p, ok := c.pools[poolKey(domain.ModNewArrivals, "")]; ok {
		sig.NewProducts = len(p)
	}
	if p, ok := c.pools[poolKey(domain.ModTopSellers, "")]; ok {
		sig.TopSellerProducts = len(reviewed(p))
	}
	return sig
}

func dealsFromMin(pool []domain.ProductSummary, limit, minPercent int) []domain.ProductSummary {
	out := []domain.ProductSummary{}
	for _, p := range pool {
		if eligibleCard(p) && discountPercent(p) >= minPercent {
			out = append(out, p)
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return discountPercent(out[i]) > discountPercent(out[j]) })
	if len(out) > limit {
		out = out[:limit]
	}
	return out
}

// reviewed keeps products with a real rating: "0 reviews" is never shown, so an unreviewed product cannot sit in Top rated.
func reviewed(in []domain.ProductSummary) []domain.ProductSummary {
	out := []domain.ProductSummary{}
	for _, p := range in {
		if p.ReviewCount > 0 && p.Rating > 0 {
			out = append(out, p)
		}
	}
	return out
}

func distinctSellerKeys(in []domain.ProductSummary) int {
	seen := map[string]bool{}
	for _, p := range in {
		seen[sellerKey(p)] = true
	}
	return len(seen)
}

func sellerKey(p domain.ProductSummary) string { return firstNonEmpty(p.SellerID, p.SellerName) }

// nearProviders fills the providers that serve the visitor's ZIP (cached per ZIP for 60 s).
func (c *composer) nearProviders(sig *domain.HomeSignals) {
	list := c.nearList()
	sig.ServiceProviders = providerCount(list)
}

func (c *composer) nearList() []port.UpBkListing {
	if c.snap.Fixture || !zip5Home(c.loc.ZIP) {
		return nil
	}
	if c.near != nil {
		return *c.near
	}
	list, _, err := c.s.nearCache.get(c.loc.ZIP, c.s.d.Clock.now(), func() ([]port.UpBkListing, error) {
		return c.src.nearServices(context.Background(), c.loc.ZIP)
	})
	if err != nil {
		list = nil
	}
	c.near = &list
	return list
}

// plan lists what to order: the shown automatic modules and the pinned staff blocks. A module a staff placement already
// covers is hidden with the placement named.
func (c *composer) plan(decisions []domain.ModuleDecision, pinned []domain.HomeBlock, covered map[string]string) ([]domain.OrderedModule, map[string]*domain.ModuleDecision) {
	autos := map[string]*domain.ModuleDecision{}
	var order []domain.OrderedModule
	for i := range decisions {
		d := &decisions[i]
		autos[d.Module] = d
		switch d.Module {
		case domain.ModHero, domain.ModContinue, domain.ModRecent:
			continue // the hero is its own field; the personal rails arrive as pinned blocks
		}
		if name, ok := covered[d.Module]; ok {
			if d.Shown {
				d.Shown, d.Reason = false, domain.ReasonNothing
				d.Detail = fmt.Sprintf("A staff placement (%s) already holds this slot, so the automatic module stays out of its way.", firstNonEmpty(name, "untitled"))
			}
			d.Pinned = true
			continue
		}
		if !d.Shown {
			continue
		}
		rule := c.st.Modules[d.Module]
		if d.Module == domain.ModDeptRail {
			for _, dept := range c.railDepartments(d.Units) {
				order = append(order, domain.OrderedModule{Key: d.Module + ":" + dept, Band: rule.Band, Weight: rule.Weight})
			}
			continue
		}
		order = append(order, domain.OrderedModule{Key: d.Module, Band: rule.Band, Weight: rule.Weight})
	}
	for i, b := range pinned {
		order = append(order, domain.OrderedModule{Key: "pin:" + b.ID, Band: pinnedBand(b), Weight: 1000 - i, Pinned: true})
	}
	return order, autos
}

func pinnedBand(b domain.HomeBlock) string {
	switch b.Type {
	case domain.BlockBasedOnOrders, domain.BlockRecentlyViewed:
		return domain.BandPersonal
	case domain.BlockMembership, domain.BlockSignIn, domain.BlockPreorders:
		return domain.BandPromo
	}
	return domain.BandDiscovery
}

// assemble builds the blocks in page order. Each automatic module picks from its pool with the seeded variety engine,
// excluding what the page already shows; one that cannot reach its minimum after the picks is hidden, with the reason.
func (c *composer) assemble(ctx context.Context, ordered []domain.OrderedModule, autos map[string]*domain.ModuleDecision, pinned []domain.HomeBlock, decisions []domain.ModuleDecision) ([]domain.HomeBlock, []domain.ModuleDecision) {
	pinnedBy := map[string]domain.HomeBlock{}
	for _, b := range pinned {
		pinnedBy["pin:"+b.ID] = b
	}
	type slot struct {
		blocks    []domain.HomeBlock
		sponsored bool
	}
	var slots []slot
	autoCount := 0
	for _, om := range ordered {
		if b, ok := pinnedBy[om.Key]; ok {
			slots = append(slots, slot{blocks: []domain.HomeBlock{b}, sponsored: b.Sponsored})
			continue
		}
		modKey, arg, _ := strings.Cut(om.Key, ":")
		d := autos[modKey]
		if d == nil {
			continue
		}
		if len(slots) >= c.st.Caps.MaxModules {
			d.Shown, d.Reason, d.Detail = false, domain.ReasonPageFull, fmt.Sprintf("The page already has the most modules staff allow (%d).", c.st.Caps.MaxModules)
			continue
		}
		blocks, pool, picked, why := c.build(ctx, modKey, arg)
		d.Pool, d.Picked = pool, picked
		if len(blocks) == 0 {
			d.Shown, d.Reason = false, why
			d.Detail = fmt.Sprintf("%s has too few items left after the page's other modules and the seller share (%d of the %d it needs).", d.Label, picked, max(d.Need, MinRailItems))
			if why == domain.ReasonFewSellers {
				d.Detail = fmt.Sprintf("%s needs products from %d sellers; its candidates come from fewer.", d.Label, c.st.Modules[modKey].MinSellers)
			}
			continue
		}
		autoCount++
		slots = append(slots, slot{blocks: blocks})
	}
	flags := make([]bool, len(slots))
	for i, sl := range slots {
		flags[i] = sl.sponsored
	}
	keep := domain.CapSponsored(flags, c.st.Caps.SponsoredPerOrg)
	var out []domain.HomeBlock
	for i, sl := range slots {
		if !keep[i] {
			decisions = append(decisions, domain.ModuleDecision{Module: "placement:" + sl.blocks[0].ID, Label: "Sponsored module " + firstNonEmpty(sl.blocks[0].Title, sl.blocks[0].Type),
				Reason: domain.ReasonSponsored, Detail: fmt.Sprintf("At most 1 sponsored module is shown per %d organic modules; this one was left out.", c.st.Caps.SponsoredPerOrg), Pinned: true})
			continue
		}
		out = append(out, sl.blocks...)
	}
	for i := range out {
		b := &out[i]
		mod := b.Module
		if mod == "" {
			mod = trackedStaffModule
			b.Scope = domain.ScopeShared
		}
		b.Tracking = &domain.HomeTracking{Module: mod, Position: i + 1, Variant: variantOrControl(c.req.Variant)}
	}
	// Order numbers and the pinned decisions, then the seen keys.
	for i := range decisions {
		decisions[i].Order = 0
	}
	for _, b := range pinned {
		decisions = append(decisions, domain.ModuleDecision{Module: "placement:" + b.ID, Label: "Staff placement: " + firstNonEmpty(b.Title, b.Type), Shown: true,
			Reason: domain.ReasonShown, Detail: "Placed by staff; it keeps its priority and wins ties over automatic modules.", Pinned: true})
	}
	pos := 0
	for _, b := range out {
		pos++
		for i := range decisions {
			if decisions[i].Module == b.Module && b.Module != "" {
				decisions[i].Order = pos
			}
		}
	}
	// A staff pin dropped by the sponsored cap is not "shown".
	for i := range decisions {
		if strings.HasPrefix(decisions[i].Module, "placement:") && decisions[i].Shown {
			id := strings.TrimPrefix(decisions[i].Module, "placement:")
			present := false
			for _, b := range out {
				present = present || b.ID == id
			}
			if !present {
				decisions[i].Shown, decisions[i].Reason = false, domain.ReasonSponsored
				decisions[i].Detail = "Left out by the sponsored cap."
			}
		}
	}
	_ = autoCount
	return out, decisions
}

// build makes the block(s) of one automatic module. It returns the pool size, the items picked and, when it cannot be
// shown, the reason.
func (c *composer) build(ctx context.Context, key, arg string) ([]domain.HomeBlock, int, int, string) {
	rule := c.st.Modules[key]
	tracking := func(b domain.HomeBlock) domain.HomeBlock { b.Module, b.Scope = key, domain.ScopeShared; return b }
	switch key {
	case domain.ModBrandHero:
		return []domain.HomeBlock{tracking(domain.HomeBlock{ID: "brand-hero", Type: domain.BlockBrandHero, TitleKey: domain.KeyBrandHeroTitle, SubKey: domain.KeyBrandHeroSub,
			CTAKey: domain.KeyBrandHeroCTA, Href: "/sell", TextOnly: true})}, 1, 1, ""
	case domain.ModSellBand:
		return []domain.HomeBlock{tracking(domain.HomeBlock{ID: "sell-band", Type: domain.BlockSellBand, TitleKey: domain.KeySellBandTitle, BodyKey: domain.KeySellBandBody,
			CTAKey: domain.KeySellBandCTA, Href: "/sell"})}, 1, 1, ""
	case domain.ModDepartments:
		return c.buildDepartments(rule, tracking)
	case domain.ModCommunities:
		return c.buildCommunities(rule, tracking)
	case domain.ModServices:
		return c.buildServices(rule, tracking)
	}
	// Product rails.
	dept := arg
	spec := map[string]struct{ title, typ string }{
		domain.ModDeals: {domain.KeyDealsTitle, domain.BlockDeals}, domain.ModNewArrivals: {domain.KeyNewArrivalsTitle, domain.BlockRail},
		domain.ModTopSellers: {domain.KeyTopSellersTitle, domain.BlockRail}, domain.ModGroceries: {domain.KeyGroceriesTitle, domain.BlockRail},
		domain.ModDeptRail: {domain.KeyTopInTitle, domain.BlockRail},
	}[key]
	if spec.title == "" {
		return nil, 0, 0, domain.ReasonNothing
	}
	if key == domain.ModGroceries {
		dept = c.groceryDept()
	}
	pool := c.pools[poolKey(key, dept)]
	switch key {
	case domain.ModDeals:
		pool = dealsFromMin(pool, maxPoolItems, c.st.Caps.DealMinPercent)
	case domain.ModTopSellers:
		pool = reviewed(pool)
	}
	if rule.MinSellers > 0 && distinctSellerKeys(pool) < rule.MinSellers {
		return nil, len(pool), 0, domain.ReasonFewSellers
	}
	items := c.pick(key, pool, rule.Show)
	if len(items) < MinRailItems {
		return nil, len(pool), len(items), domain.ReasonThin
	}
	for _, p := range items {
		c.used[p.ATIN] = true
		c.seen = appendSeen(c.seen, domain.ShortKey(p.ATIN))
	}
	title := spec.title
	b := tracking(domain.HomeBlock{ID: key, Type: spec.typ, TitleKey: title, CTAKey: domain.KeySeeAll, Href: "/s"})
	if dept != "" {
		b.ID = key + "-" + domain.DepartmentKey(dept)
		b.Href = "/s?dept=" + url.QueryEscape(dept)
		if key == domain.ModDeptRail {
			b.TitleParams = map[string]string{"department": domain.DepartmentLabels(dept, c.st.DepartmentLabels).For(c.locale, dept)}
		}
	}
	switch key {
	case domain.ModDeals:
		b.Href = "/s?sort=rating" // no end time: a countdown only when staff set one on a placement
	case domain.ModNewArrivals:
		b.Href = "/s?sort=newest"
	case domain.ModTopSellers:
		b.Href = "/s?sort=rating"
	}
	b.Rail = &domain.Rail{ID: b.ID, Href: b.Href, Items: items}
	return []domain.HomeBlock{b}, len(pool), len(items), ""
}

func appendSeen(seen []string, k string) []string {
	if len(seen) >= seenKeyCap {
		return seen
	}
	return append(seen, k)
}

// pick runs the variety engine over a product pool: excluding what the page and the visitor's own history already show.
func (c *composer) pick(module string, pool []domain.ProductSummary, show int) []domain.ProductSummary {
	items := make([]domain.VarietyItem, 0, len(pool))
	byKey := map[string]domain.ProductSummary{}
	for _, p := range pool {
		if !eligibleCard(p) {
			continue
		}
		items = append(items, domain.VarietyItem{Key: p.ATIN, Seller: sellerKey(p), Quality: domain.QualityOf(p, 0)})
		byKey[p.ATIN] = p
	}
	ex := make(map[string]bool, len(c.used)+len(c.exclude))
	for k := range c.used {
		ex[k] = true
	}
	for k := range c.exclude {
		ex[k] = true
	}
	picked := domain.PickVariety(items, domain.VarietyParams{Seed: c.vis.seed, Module: module, Show: show, Novelty: c.vis.novelty,
		NoveltyPenalty: c.st.Caps.NoveltyPenalty, Exclude: ex, SellerShare: c.st.Caps.SellerShare, Deterministic: c.vis.deterministic})
	out := make([]domain.ProductSummary, len(picked))
	for i, p := range picked {
		out[i] = byKey[p.Key]
	}
	return out
}

// buildDepartments makes the department tiles: every department with eligible products is a candidate; the biggest are
// favoured and the set rotates by visit when more qualify than the module shows.
func (c *composer) buildDepartments(rule domain.ModuleRule, tag func(domain.HomeBlock) domain.HomeBlock) ([]domain.HomeBlock, int, int, string) {
	var cand []snapDept
	for _, d := range c.snap.Departments {
		if d.Count >= max(rule.UnitMinProduct, 1) && d.Sellers >= rule.UnitMinSellers && realPhoto(d.Image) {
			cand = append(cand, d)
		}
	}
	maxN := 1
	for _, d := range cand {
		maxN = max(maxN, d.Count)
	}
	items := make([]domain.VarietyItem, len(cand))
	byKey := map[string]snapDept{}
	for i, d := range cand {
		items[i] = domain.VarietyItem{Key: d.Key, Quality: 0.3 + 0.7*float64(d.Count)/float64(maxN)}
		byKey[d.Key] = d
	}
	picked := domain.PickVariety(items, domain.VarietyParams{Seed: c.vis.seed, Module: domain.ModDepartments, Show: rule.Show, Deterministic: c.vis.deterministic})
	if len(picked) < max(rule.MinUnits, 2) {
		return nil, len(cand), len(picked), domain.ReasonNoCover
	}
	cells := make([]domain.BlockCell, 0, len(picked))
	for _, p := range picked {
		d := byKey[p.Key]
		labels := domain.DepartmentLabels(d.Name, c.st.DepartmentLabels)
		cell := domain.BlockCell{Label: labels.For(c.locale, d.Name), Href: "/s?dept=" + url.QueryEscape(d.Name), ImageURL: d.Image, Key: d.Key, Labels: labels, Count: d.Count}
		if _, ok := deptLabelKey(d.Key); ok {
			cell.LabelKey = "dept." + d.Key
		}
		cells = append(cells, cell)
		c.seen = appendSeen(c.seen, domain.ShortKey("dept:"+d.Key))
	}
	return []domain.HomeBlock{tag(domain.HomeBlock{ID: "department-tiles", Type: domain.BlockDepartmentTiles, TitleKey: domain.KeyDepartmentsTitle, Cells: cells})}, len(cand), len(cells), ""
}

func deptLabelKey(key string) (string, bool) {
	_, ok := domain.DepartmentLabels(key, nil)["fr"]
	return key, ok && domain.DepartmentLabels(key, nil)["fr"] != ""
}

func (c *composer) buildCommunities(rule domain.ModuleRule, tag func(domain.HomeBlock) domain.HomeBlock) ([]domain.HomeBlock, int, int, string) {
	var cand []snapComm
	for _, cm := range c.snap.Communities {
		if cm.Count >= rule.UnitMinProduct && cm.Sellers >= rule.UnitMinSellers && realPhoto(c.communityImage(cm)) {
			cand = append(cand, cm)
		}
	}
	items := make([]domain.VarietyItem, len(cand))
	byKey := map[string]snapComm{}
	maxN := 1
	for _, cm := range cand {
		maxN = max(maxN, cm.Count)
	}
	for i, cm := range cand {
		items[i] = domain.VarietyItem{Key: cm.Key, Quality: 0.3 + 0.7*float64(cm.Count)/float64(maxN)}
		byKey[cm.Key] = cm
	}
	picked := domain.PickVariety(items, domain.VarietyParams{Seed: c.vis.seed, Module: domain.ModCommunities, Show: rule.Show, Deterministic: c.vis.deterministic})
	if len(picked) < max(rule.MinUnits, 1) {
		return nil, len(cand), len(picked), domain.ReasonThin
	}
	cells := make([]domain.BlockCell, 0, len(picked))
	for _, p := range picked {
		cm := byKey[p.Key]
		labels := c.res.Labels(cm.Key)
		cells = append(cells, domain.BlockCell{Label: labels.For(c.locale, cm.Key), Href: "/communities/" + url.PathEscape(cm.Key), ImageURL: c.communityImage(cm), Key: cm.Key, Labels: labels, Count: cm.Count})
		c.seen = appendSeen(c.seen, domain.ShortKey("community:"+cm.Key))
	}
	return []domain.HomeBlock{tag(domain.HomeBlock{ID: "communities", Type: domain.BlockCommunityTiles, TitleKey: domain.KeyCommunitiesTitle, Cells: cells})}, len(cand), len(cells), ""
}

// communityImage is the editorial image when staff set one that is a real photo, else the best top product's photo.
func (c *composer) communityImage(cm snapComm) *string {
	if _, e := c.res.Resolve(cm.Key); e != nil && realPhoto(e.ImageURL) {
		return e.ImageURL
	}
	return cm.Image
}

func (c *composer) buildServices(rule domain.ModuleRule, tag func(domain.HomeBlock) domain.HomeBlock) ([]domain.HomeBlock, int, int, string) {
	near := c.nearList()
	list, titleKey, params := near, domain.KeyServicesNear, map[string]string{"city": c.loc.City}
	if len(list) == 0 || strings.TrimSpace(c.loc.City) == "" {
		if len(near) == 0 {
			list = c.snap.Listings
		}
		titleKey, params = domain.KeyServicesPopular, nil
	}
	items := make([]domain.VarietyItem, 0, len(list))
	byKey := map[string]port.UpBkListing{}
	for _, l := range list {
		q := 0.5
		if l.RatingCount > 0 && l.Rating > 0 {
			q = 0.4 + 0.6*min(l.Rating, 5)/5
		}
		items = append(items, domain.VarietyItem{Key: l.ServiceID, Seller: firstNonEmpty(l.OrgID, l.ProviderName), Quality: q})
		byKey[l.ServiceID] = l
	}
	picked := domain.PickVariety(items, domain.VarietyParams{Seed: c.vis.seed, Module: domain.ModServices, Show: rule.Show, Novelty: c.vis.novelty,
		NoveltyPenalty: c.st.Caps.NoveltyPenalty, SellerShare: c.st.Caps.SellerShare, Deterministic: c.vis.deterministic})
	if len(picked) == 0 {
		return nil, len(items), 0, domain.ReasonNothing
	}
	cards := make([]domain.ServiceCard, 0, len(picked))
	for _, p := range picked {
		l := byKey[p.Key]
		card := domain.ServiceCard{ID: l.ServiceID, Title: l.Name, Provider: l.ProviderName, City: l.Location, Currency: l.Currency, Href: "/services/" + url.PathEscape(l.ServiceID)}
		if l.PriceFromMinor > 0 {
			v := l.PriceFromMinor
			card.FromPriceMinor = &v
		}
		if realPhoto(&l.ImageURL) {
			img := l.ImageURL
			card.ImageURL = &img
		}
		cards = append(cards, card)
		c.seen = appendSeen(c.seen, domain.ShortKey("svc:"+l.ServiceID))
	}
	return []domain.HomeBlock{tag(domain.HomeBlock{ID: "services", Type: domain.BlockServiceCards, TitleKey: titleKey, TitleParams: params, CTAKey: domain.KeyServicesCTA, Href: "/services", Services: cards})},
		len(items), len(cards), ""
}

// previousShown / rememberShown keep last composition's shown set for hysteresis (per process; a boundary flap only
// needs the previous answer of this replica).
func (s *siteServiceImpl) previousShown() map[string]bool {
	s.prevMu.Lock()
	defer s.prevMu.Unlock()
	out := make(map[string]bool, len(s.prevShown))
	for k, v := range s.prevShown {
		out[k] = v
	}
	return out
}

func (s *siteServiceImpl) rememberShown(decisions []domain.ModuleDecision) {
	s.prevMu.Lock()
	defer s.prevMu.Unlock()
	for _, d := range decisions {
		if domain.IsModule(d.Module) {
			s.prevShown[d.Module] = d.Shown
		}
	}
}
