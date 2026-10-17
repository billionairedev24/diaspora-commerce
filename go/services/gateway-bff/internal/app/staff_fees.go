package app

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"slices"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// StaffFeesService is the fee-schedule editor of Platform configuration → Fees (contracts/openapi/gateway-bff-staff.yaml,
// tag Fees): the versions with their status, the categories from catalog's taxonomy, seller organisations to override,
// creating a version and cancelling a scheduled one. payments owns the rules (immutable versions, never backdated,
// Retail never charged); the BFF validates the draft first so the console can point at the field.
type StaffFeesService interface {
	Overview(ctx context.Context, c *domain.Caller) (domain.FeesOverview, error)
	SearchOrgs(ctx context.Context, c *domain.Caller, q string) (domain.FeeOrgSearch, error)
	Create(ctx context.Context, c *domain.Caller, d domain.FeeScheduleDraft, idem string) (domain.FeeScheduleView, error)
	Cancel(ctx context.Context, c *domain.Caller, id, reason string) (domain.FeeScheduleView, error)
	// Quote prices one AfroTransact Delivery trip under the live schedule (payments POST /v1/delivery-pricing/quote).
	Quote(ctx context.Context, c *domain.Caller, q domain.DeliveryQuoteRequest) (domain.DeliveryQuote, error)
	// NegotiatedRates lists negotiated commission / connection-fee rates (server-driven table; orgId for one seller).
	NegotiatedRates(ctx context.Context, c *domain.Caller, q NegotiatedRateQuery) (domain.NegotiatedRatePage, error)
	// CreateNegotiatedRate negotiates a rate with an organisation (payments:configure, a reason is required; audited).
	CreateNegotiatedRate(ctx context.Context, c *domain.Caller, d domain.NegotiatedRateDraft) (domain.NegotiatedRateView, error)
	// EndNegotiatedRate ends a scheduled or active rate now, with a reason (audited).
	EndNegotiatedRate(ctx context.Context, c *domain.Caller, id, reason string) (domain.NegotiatedRateView, error)
	// Calculate is the rate calculator: what one organisation pays on one kind and category now, and why.
	Calculate(ctx context.Context, c *domain.Caller, orgID, category, kind string) (domain.FeeRateQuote, error)
	// NegotiatePlan creates a private (invite-only) version of a catalogue plan at a negotiated price for one
	// organisation and invites it (seller service's plan catalogue; plans:manage).
	NegotiatePlan(ctx context.Context, c *domain.Caller, orgID string, d domain.NegotiatedPlanDraft) (domain.NegotiatedPlan, error)
	// ImportTaxRates upserts state and local rows from CSV (tax-returns POST /v1/tax/rates/import; tax:configure).
	ImportTaxRates(ctx context.Context, c *domain.Caller, csv string) (domain.TaxRatesImportResult, error)
}

type staffFeesImpl struct {
	r      *staffResolver
	retail string
}

var _ StaffFeesService = (*staffFeesImpl)(nil)

// NewStaffFeesService returns the fee-schedule editor use case.
func NewStaffFeesService(d StaffDeps) StaffFeesService {
	return &staffFeesImpl{r: newStaffResolver(d), retail: strings.TrimSpace(d.RetailOrgID)}
}

const (
	feesModule          = "fees"
	paymentsConfigure   = "payments:configure"
	taxConfigure        = "tax:configure"
	feeExampleSaleMinor = 10_000
	// orgDirectoryPages caps how much of seller's organisation directory one search or name lookup reads (200 per page).
	orgDirectoryPages = 5
)

// paymentsSchedule is payments' FeeScheduleResponse.
type paymentsSchedule struct {
	ID                   string         `json:"id"`
	Version              int            `json:"version"`
	EffectiveFrom        time.Time      `json:"effectiveFrom"`
	Currency             string         `json:"currency"`
	CategoryBps          map[string]int `json:"categoryBps"`
	DefaultCommissionBps int            `json:"defaultCommissionBps"`
	Overrides            []struct {
		OrgID         string `json:"orgId"`
		Category      string `json:"category"`
		CommissionBps int    `json:"commissionBps"`
	} `json:"overrides"`
	CardPctBps                int                       `json:"cardPctBps"`
	CardFixedMinor            int64                     `json:"cardFixedMinor"`
	FbaPerUnitMinor           int64                     `json:"fbaPerUnitMinor"`
	ReverseCommissionOnRefund bool                      `json:"reverseCommissionOnRefund"`
	DeliveryPricing           *domain.DeliveryPricing   `json:"deliveryPricing"`
	PlanBps                   map[string]map[string]int `json:"planBps"`
	PlanFbaMinor              map[string]int64          `json:"planFbaMinor"`
	ConnectionFee             *struct {
		DefaultBps  int                       `json:"defaultBps"`
		CategoryBps map[string]int            `json:"categoryBps"`
		PlanBps     map[string]map[string]int `json:"planBps"`
	} `json:"connectionFee"`
	Note         string     `json:"note"`
	CreatedBy    string     `json:"createdBy"`
	CreatedAt    *time.Time `json:"createdAt"`
	CancelledAt  *time.Time `json:"cancelledAt"`
	CancelledBy  string     `json:"cancelledBy"`
	CancelReason string     `json:"cancelReason"`
}

func (p paymentsSchedule) cancelled() bool { return p.CancelledAt != nil }

// feeTimeline is payments' history (newest version first) with the version in force and the latest live one.
type feeTimeline struct {
	all    []paymentsSchedule
	active *paymentsSchedule
	latest *paymentsSchedule // newest version that is not cancelled
}

func timelineOf(all []paymentsSchedule, now time.Time) feeTimeline {
	sort.SliceStable(all, func(i, j int) bool { return all[i].Version > all[j].Version })
	t := feeTimeline{all: all}
	for i := range all {
		s := &all[i]
		if s.cancelled() {
			continue
		}
		if t.latest == nil {
			t.latest = s
		}
		if !s.EffectiveFrom.After(now) && (t.active == nil || s.EffectiveFrom.After(t.active.EffectiveFrom) ||
			(s.EffectiveFrom.Equal(t.active.EffectiveFrom) && s.Version > t.active.Version)) {
			t.active = s
		}
	}
	return t
}

func (s *staffFeesImpl) history(ctx context.Context, sc staffCtx) ([]paymentsSchedule, error) {
	var all []paymentsSchedule
	if err := sc.get(ctx, sc.up.Payments, "/v1/fee-schedules", nil, &all); err != nil {
		return nil, err
	}
	return all, nil
}

func canConfigureFees(a domain.StaffAccess) bool {
	return a.Can(paymentsConfigure) && a.Allows(feesModule, domain.LevelEdit)
}

func (s *staffFeesImpl) requireConfigure(ctx context.Context, c *domain.Caller) (staffCtx, error) {
	sc, err := s.r.require(ctx, c, feesModule, domain.LevelEdit)
	if err != nil {
		return sc, err
	}
	if !sc.access.Can(paymentsConfigure) {
		return sc, needsPermission(paymentsConfigure)
	}
	return sc, nil
}

func (s *staffFeesImpl) requireTaxConfigure(ctx context.Context, c *domain.Caller) (staffCtx, error) {
	sc, err := s.r.require(ctx, c, feesModule, domain.LevelEdit)
	if err != nil {
		return sc, err
	}
	if !sc.access.Can(taxConfigure) {
		return sc, needsPermission(taxConfigure)
	}
	return sc, nil
}

// ---------------------------------------------------------------- overview

func (s *staffFeesImpl) Overview(ctx context.Context, c *domain.Caller) (domain.FeesOverview, error) {
	sc, err := s.r.require(ctx, c, feesModule, domain.LevelView)
	if err != nil {
		return domain.FeesOverview{}, err
	}
	var (
		wg       sync.WaitGroup
		all      []paymentsSchedule
		histErr  error
		taxonomy []domain.FeeCategory
		taxErr   error
	)
	wg.Go(func() { all, histErr = s.history(ctx, sc) })
	wg.Go(func() { taxonomy, taxErr = s.departments(ctx, sc) })
	wg.Wait()
	if histErr != nil {
		return domain.FeesOverview{}, histErr
	}
	t := timelineOf(all, sc.now)
	names, namesOK := s.orgNames(ctx, sc, t.all)
	cats := mergeCategories(taxonomy, t.all)
	labels := map[string]string{}
	for _, c := range cats {
		labels[c.Key] = c.Label
	}
	canConfigure := canConfigureFees(sc.access)
	plans, plansErr := s.catalogue(ctx, sc)
	out := domain.FeesOverview{GeneratedAt: sc.now, Currency: domain.Currency, CanConfigure: canConfigure, Schedules: []domain.FeeScheduleView{},
		Categories: cats, TaxonomyAvailable: taxErr == nil, OrgNamesAvailable: namesOK, Limits: domain.DefaultFeeLimits, ExampleSaleMinor: feeExampleSaleMinor,
		Retail:              domain.FeeRetail{OrgID: s.retail, Name: PlatformSellerName, Note: domain.RetailFeesNote},
		ReverseOnRefundHelp: "When on, a refund gives the seller back the commission on the refunded amount."}
	for _, p := range t.all {
		v := s.view(p, t, sc.now, labels, names, canConfigure)
		out.Schedules = append(out.Schedules, v)
		if t.active != nil && p.ID == t.active.ID {
			cur := v
			out.Current = &cur
		}
	}
	out.Plans, out.PlanWarnings = planChecks(plans, out.Current)
	out.PlansAvailable = plansErr == nil
	out.ServiceCategories = serviceCategories(t.all)
	out.MinEffectiveFrom, out.ImmediateAllowed = sc.now, true
	if t.latest != nil {
		base := s.view(*t.latest, t, sc.now, labels, names, canConfigure)
		out.Base = &base
		if t.latest.EffectiveFrom.After(sc.now) {
			out.MinEffectiveFrom, out.ImmediateAllowed = t.latest.EffectiveFrom, false
		}
	}
	return out, nil
}

func (s *staffFeesImpl) view(p paymentsSchedule, t feeTimeline, now time.Time, labels, names map[string]string, canConfigure bool) domain.FeeScheduleView {
	isActive := t.active != nil && t.active.ID == p.ID
	status := domain.FeeStatusOf(p.cancelled(), p.EffectiveFrom, isActive, now)
	label := func(k string) string {
		if l, ok := labels[k]; ok {
			return l
		}
		return domain.FeeCategoryLabel(k)
	}
	v := domain.FeeScheduleView{ID: p.ID, Version: p.Version, Status: status, EffectiveFrom: p.EffectiveFrom, Currency: firstNonEmpty(p.Currency, domain.Currency),
		DefaultCommissionBps: p.DefaultCommissionBps, CategoryRates: []domain.FeeCategoryRate{}, Overrides: []domain.FeeOverride{}, CardPctBps: p.CardPctBps,
		CardFixedMinor: p.CardFixedMinor, FbaPerUnitMinor: p.FbaPerUnitMinor, ReverseCommissionOnRefund: p.ReverseCommissionOnRefund, Note: strPtr(p.Note),
		CreatedBy: p.CreatedBy, CreatedAt: p.CreatedAt, CancelledAt: p.CancelledAt, CancelledBy: strPtr(p.CancelledBy), CancelReason: strPtr(p.CancelReason),
		Cancellable: canConfigure && status == domain.FeeStatusScheduled, PlanRates: domain.PlanRatesOf(p.PlanBps, label), PlanFba: domain.PlanFbaOf(p.PlanFbaMinor)}
	if cf := p.ConnectionFee; cf != nil {
		section := &domain.FeeConnectionFee{DefaultBps: cf.DefaultBps, CategoryRates: []domain.FeeRate{}, PlanRates: domain.PlanRatesOf(cf.PlanBps, label)}
		keys := make([]string, 0, len(cf.CategoryBps))
		for k := range cf.CategoryBps {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			section.CategoryRates = append(section.CategoryRates, domain.FeeRate{Category: k, Label: label(k), Bps: cf.CategoryBps[k]})
		}
		v.ConnectionFee = section
	}
	keys := make([]string, 0, len(p.CategoryBps))
	for k := range p.CategoryBps {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		v.CategoryRates = append(v.CategoryRates, domain.FeeCategoryRate{Category: k, Label: label(k), CommissionBps: p.CategoryBps[k]})
	}
	for _, o := range p.Overrides {
		ov := domain.FeeOverride{OrgID: o.OrgID, OrgName: strPtr(names[o.OrgID]), CategoryLabel: "All categories", CommissionBps: o.CommissionBps}
		if o.OrgID == s.retail && s.retail != "" {
			ov.OrgName = strPtr(PlatformSellerName)
		}
		if cat := domain.NormalizeFeeCategory(o.Category); cat != "" && cat != domain.AllCategories {
			ov.Category, ov.CategoryLabel = &cat, label(cat)
		}
		v.Overrides = append(v.Overrides, ov)
	}
	if dp := p.DeliveryPricing; dp != nil {
		out := domain.DeliveryPricing{Enabled: dp.Enabled, MaxRadiusMiles: dp.MaxRadiusMiles, SellerOverrides: []domain.DeliveryFeeOverride{}}
		if dp.CustomerCharge != nil {
			t := dp.CustomerCharge.Normalized()
			out.CustomerCharge = &t
		}
		if dp.SellerFee != nil {
			t := dp.SellerFee.Normalized()
			out.SellerFee = &t
		}
		for _, o := range dp.SellerOverrides {
			out.SellerOverrides = append(out.SellerOverrides, domain.DeliveryFeeOverride{OrgID: o.OrgID, OrgName: strPtr(names[o.OrgID]), SellerFee: o.SellerFee.Normalized()})
		}
		sort.SliceStable(out.SellerOverrides, func(i, j int) bool {
			a, b := out.SellerOverrides[i], out.SellerOverrides[j]
			return strings.ToLower(deref(a.OrgName, a.OrgID)) < strings.ToLower(deref(b.OrgName, b.OrgID))
		})
		v.DeliveryPricing = &out
	}
	sort.SliceStable(v.Overrides, func(i, j int) bool {
		a, b := v.Overrides[i], v.Overrides[j]
		an, bn := strings.ToLower(deref(a.OrgName, a.OrgID)), strings.ToLower(deref(b.OrgName, b.OrgID))
		if an != bn {
			return an < bn
		}
		return deref(a.Category, "") < deref(b.Category, "")
	})
	return v
}

func deref(p *string, def string) string {
	if p == nil {
		return def
	}
	return *p
}

// departments reads catalog's category tree; departments are the commission categories (lower-cased names), and
// bookings' services always pay commission under "services".
func (s *staffFeesImpl) departments(ctx context.Context, sc staffCtx) ([]domain.FeeCategory, error) {
	var tree []jobj
	if err := sc.get(ctx, sc.up.Catalog, "/v1/categories", nil, &tree); err != nil {
		return nil, err
	}
	out := []domain.FeeCategory{}
	for _, n := range tree {
		if !strings.EqualFold(jstr(n, "level"), "DEPARTMENT") || jstr(n, "parentId") != "" {
			continue
		}
		name := strings.TrimSpace(jstr(n, "name"))
		if name == "" {
			continue
		}
		out = append(out, domain.FeeCategory{Key: domain.NormalizeFeeCategory(name), Label: name, Source: "TAXONOMY"})
	}
	return out, nil
}

// mergeCategories: taxonomy departments, then "services", then any category a schedule already uses (unknown to the
// taxonomy), each once, sorted by label.
func mergeCategories(taxonomy []domain.FeeCategory, all []paymentsSchedule) []domain.FeeCategory {
	out := []domain.FeeCategory{}
	seen := map[string]bool{}
	add := func(c domain.FeeCategory) {
		if c.Key == "" || c.Key == domain.AllCategories || seen[c.Key] {
			return
		}
		seen[c.Key] = true
		out = append(out, c)
	}
	for _, c := range taxonomy {
		add(c)
	}
	add(domain.FeeCategory{Key: "services", Label: "Services", Source: "PLATFORM"})
	for _, p := range all {
		for k := range p.CategoryBps {
			key := domain.NormalizeFeeCategory(k)
			add(domain.FeeCategory{Key: key, Label: domain.FeeCategoryLabel(key), Source: "SCHEDULE"})
		}
		for _, o := range p.Overrides {
			key := domain.NormalizeFeeCategory(o.Category)
			add(domain.FeeCategory{Key: key, Label: domain.FeeCategoryLabel(key), Source: "SCHEDULE"})
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return strings.ToLower(out[i].Label) < strings.ToLower(out[j].Label) })
	return out
}

// ---------------------------------------------------------------- organisations

// directory reads seller's organisation directory (sellers:directory or staff), up to orgDirectoryPages pages.
func (s *staffFeesImpl) directory(ctx context.Context, sc staffCtx, stop func([]jobj) bool) ([]jobj, error) {
	out := []jobj{}
	for page := range orgDirectoryPages {
		var p jobj
		if err := sc.get(ctx, sc.up.Seller, "/v1/orgs/directory", url.Values{"page": {strconv.Itoa(page)}, "size": {"200"}}, &p); err != nil {
			return out, err
		}
		items := objs(p, "items")
		out = append(out, items...)
		total := int(num(p, "total"))
		if len(items) < 200 || (total > 0 && len(out) >= total) || (stop != nil && stop(out)) {
			break
		}
	}
	return out, nil
}

// orgNames names the organisations that have overrides (best effort: ids stay visible when seller is unavailable).
func (s *staffFeesImpl) orgNames(ctx context.Context, sc staffCtx, all []paymentsSchedule) (map[string]string, bool) {
	want := map[string]bool{}
	for _, p := range all {
		for _, o := range p.Overrides {
			want[o.OrgID] = true
		}
		if p.DeliveryPricing != nil {
			for _, o := range p.DeliveryPricing.SellerOverrides {
				want[o.OrgID] = true
			}
		}
	}
	names := map[string]string{}
	if len(want) == 0 {
		return names, true
	}
	found := func(items []jobj) bool {
		n := 0
		for _, it := range items {
			if want[jstr(it, "id")] {
				n++
			}
		}
		return n >= len(want)
	}
	items, err := s.directory(ctx, sc, found)
	for _, it := range items {
		if id := jstr(it, "id"); want[id] {
			names[id] = firstNonEmpty(jstr(it, "displayName"), id)
		}
	}
	return names, err == nil
}

func (s *staffFeesImpl) SearchOrgs(ctx context.Context, c *domain.Caller, q string) (domain.FeeOrgSearch, error) {
	q = strings.TrimSpace(q)
	if len([]rune(q)) < 2 || len(q) > 120 {
		return domain.FeeOrgSearch{}, domain.ValidationProblem(map[string]string{"q": "Type 2 to 120 characters"})
	}
	sc, err := s.r.require(ctx, c, feesModule, domain.LevelView)
	if err != nil {
		return domain.FeeOrgSearch{}, err
	}
	needle := strings.ToLower(q)
	out := domain.FeeOrgSearch{Items: []domain.FeeOrgHit{}}
	retailReason := domain.RetailFeesNote
	if strings.Contains(strings.ToLower(PlatformSellerName), needle) || (s.retail != "" && strings.EqualFold(q, s.retail)) {
		out.Items = append(out.Items, domain.FeeOrgHit{OrgID: s.retail, Name: PlatformSellerName, Kind: "RETAIL", Status: "ACTIVE", Retail: true, Reason: &retailReason})
	}
	items, err := s.directory(ctx, sc, nil)
	if err != nil && len(items) == 0 {
		return domain.FeeOrgSearch{}, err
	}
	for _, it := range items {
		id, name := jstr(it, "id"), firstNonEmpty(jstr(it, "displayName"), jstr(it, "id"))
		if id == "" || (s.retail != "" && id == s.retail) {
			continue
		}
		if !strings.Contains(strings.ToLower(name), needle) && !strings.EqualFold(id, q) {
			continue
		}
		out.Items = append(out.Items, domain.FeeOrgHit{OrgID: id, Name: name, Kind: strings.ToUpper(jstr(it, "kind")), Status: strings.ToUpper(jstr(it, "status")), Selectable: true})
		if len(out.Items) >= 20 {
			break
		}
	}
	return out, nil
}

// ---------------------------------------------------------------- create and cancel

func (s *staffFeesImpl) Create(ctx context.Context, c *domain.Caller, d domain.FeeScheduleDraft, idem string) (domain.FeeScheduleView, error) {
	sc, err := s.requireConfigure(ctx, c)
	if err != nil {
		return domain.FeeScheduleView{}, err
	}
	sc.idem = idem
	all, err := s.history(ctx, sc)
	if err != nil {
		return domain.FeeScheduleView{}, err
	}
	t := timelineOf(all, sc.now)
	rules := domain.FeeDraftRules{Now: sc.now, MinEffectiveFrom: sc.now, ImmediateAllowed: true, RetailOrgID: s.retail, Limits: domain.DefaultFeeLimits}
	if t.latest != nil && t.latest.EffectiveFrom.After(sc.now) {
		rules.MinEffectiveFrom, rules.ImmediateAllowed = t.latest.EffectiveFrom, false
	}
	if errs := domain.ValidateFeeDraft(d, rules); len(errs) > 0 {
		return domain.FeeScheduleView{}, domain.ValidationProblem(errs)
	}
	cats := map[string]int{}
	for _, r := range d.CategoryRates {
		cats[domain.NormalizeFeeCategory(r.Category)] = *r.CommissionBps
	}
	overrides := []jobj{}
	for _, o := range d.Overrides {
		ov := jobj{"orgId": strings.TrimSpace(o.OrgID), "commissionBps": *o.CommissionBps}
		if o.Category != nil && domain.NormalizeFeeCategory(*o.Category) != "" {
			ov["category"] = domain.NormalizeFeeCategory(*o.Category)
		}
		overrides = append(overrides, ov)
	}
	body := jobj{"currency": domain.Currency, "categoryBps": cats, "defaultCommissionBps": *d.DefaultCommissionBps, "overrides": overrides,
		"cardPctBps": *d.CardPctBps, "cardFixedMinor": *d.CardFixedMinor, "fbaPerUnitMinor": *d.FbaPerUnitMinor,
		"reverseCommissionOnRefund": d.ReverseCommissionOnRefund, "note": strings.TrimSpace(d.Note)}
	if !d.Immediate {
		body["effectiveFrom"] = d.EffectiveFrom.UTC().Format(time.RFC3339)
	}
	if d.PlanRates != nil { // omitted = payments carries the latest live version's plan rates over
		body["planBps"] = domain.PlanBpsBody(d.PlanRates)
	}
	if d.PlanFba != nil { // omitted = payments carries the latest live version's plan FBA fees over
		body["planFbaMinor"] = domain.PlanFbaBody(d.PlanFba)
	}
	if cf := d.ConnectionFee; cf != nil {
		cats := map[string]int{}
		for _, r := range cf.CategoryRates {
			cats[domain.NormalizeFeeCategory(r.Category)] = *r.Bps
		}
		body["connectionFee"] = jobj{"defaultBps": *cf.DefaultBps, "categoryBps": cats, "planBps": domain.PlanBpsBody(cf.PlanRates)}
	}
	if dp := d.DeliveryPricing; dp != nil { // omitted = payments carries the latest live version's section over
		section := jobj{"enabled": dp.Enabled, "maxRadiusMiles": *dp.MaxRadiusMiles, "sellerOverrides": []jobj{}}
		if dp.CustomerCharge != nil {
			section["customerCharge"] = tariffBody(*dp.CustomerCharge)
		}
		if dp.SellerFee != nil {
			section["sellerFee"] = tariffBody(*dp.SellerFee)
		}
		overrides := []jobj{}
		for _, o := range dp.SellerOverrides {
			overrides = append(overrides, jobj{"orgId": strings.TrimSpace(o.OrgID), "sellerFee": tariffBody(*o.SellerFee)})
		}
		section["sellerOverrides"] = overrides
		body["deliveryPricing"] = section
	}
	var created paymentsSchedule
	if err := sc.post(ctx, sc.up.Payments, "/v1/fee-schedules", body, &created); err != nil {
		return domain.FeeScheduleView{}, err
	}
	return s.afterWrite(ctx, sc, created)
}

func (s *staffFeesImpl) Cancel(ctx context.Context, c *domain.Caller, id, reason string) (domain.FeeScheduleView, error) {
	reason = strings.TrimSpace(reason)
	if n := len([]rune(reason)); n < 3 || n > 500 {
		return domain.FeeScheduleView{}, domain.ValidationProblem(map[string]string{"reason": "Say why the version is cancelled (3 to 500 characters)"})
	}
	if id == "" || len(id) > 64 {
		return domain.FeeScheduleView{}, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such fee schedule version.")
	}
	sc, err := s.requireConfigure(ctx, c)
	if err != nil {
		return domain.FeeScheduleView{}, err
	}
	all, err := s.history(ctx, sc)
	if err != nil {
		return domain.FeeScheduleView{}, err
	}
	idx := slices.IndexFunc(all, func(p paymentsSchedule) bool { return p.ID == id })
	if idx < 0 {
		return domain.FeeScheduleView{}, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such fee schedule version.")
	}
	if p := all[idx]; p.cancelled() || !p.EffectiveFrom.After(sc.now) {
		why := fmt.Sprintf("Version %d already took effect; only scheduled versions can be cancelled.", p.Version)
		if p.cancelled() {
			why = fmt.Sprintf("Version %d is already cancelled.", p.Version)
		}
		return domain.FeeScheduleView{}, domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Conflict", why)
	}
	var cancelled paymentsSchedule
	if err := sc.post(ctx, sc.up.Payments, "/v1/fee-schedules/"+url.PathEscape(id)+"/cancel", jobj{"reason": reason}, &cancelled); err != nil {
		return domain.FeeScheduleView{}, err
	}
	return s.afterWrite(ctx, sc, cancelled)
}

// afterWrite shapes the version payments returned, with labels and names (best effort).
func (s *staffFeesImpl) afterWrite(ctx context.Context, sc staffCtx, p paymentsSchedule) (domain.FeeScheduleView, error) {
	all, err := s.history(ctx, sc)
	if err != nil || !slices.ContainsFunc(all, func(x paymentsSchedule) bool { return x.ID == p.ID }) {
		all = append(all, p)
	}
	t := timelineOf(all, sc.now)
	names, _ := s.orgNames(ctx, sc, []paymentsSchedule{p})
	labels := map[string]string{}
	if taxonomy, err := s.departments(ctx, sc); err == nil {
		for _, c := range taxonomy {
			labels[c.Key] = c.Label
		}
	}
	return s.view(p, t, sc.now, labels, names, canConfigureFees(sc.access)), nil
}

// tariffBody is payments' DeliveryTariff: only the amounts of the mode, unset ones omitted (no cap without maxMinor).
func tariffBody(t domain.DeliveryTariff) jobj {
	mode := strings.ToUpper(t.Mode)
	out := jobj{"mode": mode}
	set := func(k string, v *int64) {
		if v != nil {
			out[k] = *v
		}
	}
	if mode == domain.TariffFlat {
		set("flatPerPackageMinor", t.FlatPerPackageMinor)
		return out
	}
	set("baseMinor", t.BaseMinor)
	set("perMileMinor", t.PerMileMinor)
	set("minMinor", t.MinMinor)
	set("maxMinor", t.MaxMinor)
	bands := []jobj{}
	for _, b := range t.Bands {
		bands = append(bands, jobj{"fromMiles": b.FromMiles, "toMiles": b.ToMiles, "feeMinor": b.FeeMinor})
	}
	out["bands"] = bands
	return out
}

// ---------------------------------------------------------------- live delivery quote (S255)

func (s *staffFeesImpl) Quote(ctx context.Context, c *domain.Caller, q domain.DeliveryQuoteRequest) (domain.DeliveryQuote, error) {
	errs := map[string]string{}
	org := strings.TrimSpace(q.SellerOrgID)
	if org == "" || len(org) > 80 {
		errs["sellerOrgId"] = "Choose a seller organisation"
	}
	if q.DistanceMiles == nil || *q.DistanceMiles < 0 || *q.DistanceMiles > 1000 {
		errs["distanceMiles"] = "Enter a distance between 0 and 1,000 miles"
	}
	if q.Packages == 0 {
		q.Packages = 1
	}
	if q.Packages < 1 || q.Packages > 50 {
		errs["packages"] = "Enter 1 to 50 packages"
	}
	var at *time.Time
	if q.At != nil && *q.At != "" {
		t, err := time.Parse(time.RFC3339, *q.At)
		if err != nil {
			errs["at"] = "Enter a date and time"
		}
		at = &t
	}
	if len(errs) > 0 {
		return domain.DeliveryQuote{}, domain.ValidationProblem(errs)
	}
	sc, err := s.r.require(ctx, c, feesModule, domain.LevelView)
	if err != nil {
		return domain.DeliveryQuote{}, err
	}
	body := jobj{"sellerOrgId": org, "distanceMiles": *q.DistanceMiles, "packages": q.Packages}
	if at != nil {
		body["at"] = at.UTC().Format(time.RFC3339)
	}
	var up struct {
		Available           bool     `json:"available"`
		UnavailableReason   string   `json:"unavailableReason"`
		CustomerChargeMinor int64    `json:"customerChargeMinor"`
		SellerFeeMinor      int64    `json:"sellerFeeMinor"`
		SellerFeeSource     string   `json:"sellerFeeSource"`
		ScheduleVersion     *int     `json:"scheduleVersion"`
		MaxRadiusMiles      *float64 `json:"maxRadiusMiles"`
		Currency            string   `json:"currency"`
	}
	if err := sc.post(ctx, sc.up.Payments, "/v1/delivery-pricing/quote", body, &up); err != nil {
		return domain.DeliveryQuote{}, err
	}
	return domain.DeliveryQuote{Available: up.Available, UnavailableReason: strPtr(strings.ToUpper(up.UnavailableReason)),
		CustomerChargeMinor: up.CustomerChargeMinor, SellerFeeMinor: up.SellerFeeMinor, SellerFeeSource: strPtr(strings.ToUpper(up.SellerFeeSource)),
		ScheduleVersion: up.ScheduleVersion, MaxRadiusMiles: up.MaxRadiusMiles, Currency: firstNonEmpty(up.Currency, domain.Currency)}, nil
}

func (s *staffFeesImpl) ImportTaxRates(ctx context.Context, c *domain.Caller, csv string) (domain.TaxRatesImportResult, error) {
	sc, err := s.requireTaxConfigure(ctx, c)
	if err != nil {
		return domain.TaxRatesImportResult{}, err
	}
	csv = strings.TrimSpace(csv)
	if csv == "" {
		return domain.TaxRatesImportResult{}, domain.ValidationProblem(map[string]string{"csv": "Paste or upload a CSV file"})
	}
	var out domain.TaxRatesImportResult
	if err := sc.post(ctx, sc.up.TaxReturns, "/v1/tax/rates/import", jobj{"csv": csv}, &out); err != nil {
		return domain.TaxRatesImportResult{}, err
	}
	return out, nil
}
