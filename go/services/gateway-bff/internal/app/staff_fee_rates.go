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
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Fees & commissions › plan rates, connection fees, negotiated rates and the rate calculator, over payments' fee
// schedule and negotiated rates and the seller service's plan catalogue.

// NegotiatedRateQuery filters and pages GET /v1/staff/fees/negotiated-rates.
type NegotiatedRateQuery struct {
	OrgID  string
	Type   string
	Status string
	Q      string
	Page   int
	Size   int
}

// catalogue reads the seller service's plan catalogue (staff token); best effort for the plan-rate matrix.
func (s *staffFeesImpl) catalogue(ctx context.Context, sc staffCtx) ([]jobj, error) {
	var plans []jobj
	if err := sc.get(ctx, sc.up.Seller, "/v1/billing/plans", nil, &plans); err != nil {
		return nil, err
	}
	return plans, nil
}

// planChecks lists the catalogue's plans with what their features advertise against the configured rates of the
// version in force (sellers: product commission plan rates; providers: connection-fee plan rates).
func planChecks(plans []jobj, current *domain.FeeScheduleView) ([]domain.FeePlan, []string) {
	out, warnings := []domain.FeePlan{}, []string{}
	for _, p := range plans {
		code := jstr(p, "code")
		if code == "" {
			continue
		}
		features := []string{}
		if raw, ok := p["features"].([]any); ok {
			for _, f := range raw {
				if s, ok := f.(string); ok {
					features = append(features, s)
				}
			}
		}
		kind := strings.ToUpper(firstNonEmpty(jstr(p, "kind"), "SELLER"))
		fp := domain.FeePlan{Code: code, Kind: kind, Name: firstNonEmpty(jstr(p, "name"), domain.PlanName(code)),
			Status: strings.ToUpper(jstr(p, "status")), Visibility: strings.ToUpper(jstr(p, "visibility"))}
		text, adv := domain.AdvertisedFee(features)
		fp.FeatureText, fp.Advertised = strPtr(text), adv
		if current != nil {
			rates := current.PlanRates
			if kind == "PROVIDER" {
				rates = nil
				if current.ConnectionFee != nil {
					rates = current.ConnectionFee.PlanRates
				}
			}
			fp.Configured = domain.PlanRange(rates, code)
		}
		if w := domain.PlanFeeWarning(fp.Name, fp.Advertised, fp.Configured); w != "" {
			fp.Warning = &w
			warnings = append(warnings, w)
		}
		out = append(out, fp)
	}
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].Kind != out[j].Kind {
			return out[i].Kind > out[j].Kind // SELLER before PROVIDER
		}
		return out[i].Code < out[j].Code
	})
	return out, warnings
}

// serviceCategories are the service categories any version's connection-fee section prices, plus "services".
func serviceCategories(all []paymentsSchedule) []domain.FeeCategory {
	set := map[string]bool{"services": true}
	for _, p := range all {
		if p.ConnectionFee == nil {
			continue
		}
		for k := range p.ConnectionFee.CategoryBps {
			set[domain.NormalizeFeeCategory(k)] = true
		}
		for _, rates := range p.ConnectionFee.PlanBps {
			for k := range rates {
				if k != domain.AllCategories {
					set[domain.NormalizeFeeCategory(k)] = true
				}
			}
		}
	}
	out := []domain.FeeCategory{}
	for k := range set {
		out = append(out, domain.FeeCategory{Key: k, Label: domain.FeeCategoryLabel(k), Source: "SCHEDULE"})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Key < out[j].Key })
	return out
}

// ---------------------------------------------------------------- negotiated rates

func (s *staffFeesImpl) negotiatedView(n port.NegotiatedRateResponse, names map[string]string, canConfigure bool) domain.NegotiatedRateView {
	v := domain.NegotiatedRateView{ID: n.ID, OrgID: n.OrgID, Type: n.Type, Category: n.Category, CategoryLabel: domain.FeeCategoryLabel(n.Category),
		RateBps: n.RateBps, AmountMinor: n.AmountMinor, StartsAt: n.StartsAt, EndsAt: n.EndsAt, Status: n.Status, Reason: n.Reason, NegotiatedBy: n.NegotiatedBy,
		Reference: n.Reference, CreatedBy: n.CreatedBy, CreatedAt: n.CreatedAt, EndedAt: n.EndedAt, EndedBy: n.EndedBy, EndReason: n.EndReason,
		ExpiredAt: n.ExpiredAt, Audit: []domain.NegotiatedRateAuditEntry{},
		CanEnd: canConfigure && (n.Status == "ACTIVE" || n.Status == "SCHEDULED")}
	if name, ok := names[n.OrgID]; ok {
		v.OrgName = &name
	}
	for _, a := range n.Audit {
		v.Audit = append(v.Audit, domain.NegotiatedRateAuditEntry{Action: a.Action, Actor: a.Actor, Reason: a.Reason, At: a.At})
	}
	return v
}

// namesOf names organisations from seller's directory (best effort: ids stay visible).
func (s *staffFeesImpl) namesOf(ctx context.Context, sc staffCtx, ids []string) map[string]string {
	want := map[string]bool{}
	for _, id := range ids {
		want[id] = true
	}
	names := map[string]string{}
	if len(want) == 0 {
		return names
	}
	if s.retail != "" && want[s.retail] {
		names[s.retail] = PlatformSellerName
	}
	items, _ := s.directory(ctx, sc, func(items []jobj) bool {
		n := 0
		for _, it := range items {
			if want[jstr(it, "id")] {
				n++
			}
		}
		return n >= len(want)
	})
	for _, it := range items {
		if id := jstr(it, "id"); want[id] {
			names[id] = firstNonEmpty(jstr(it, "displayName"), id)
		}
	}
	return names
}

func (s *staffFeesImpl) NegotiatedRates(ctx context.Context, c *domain.Caller, q NegotiatedRateQuery) (domain.NegotiatedRatePage, error) {
	errs := map[string]string{}
	if q.Type != "" && q.Type != domain.NegotiatedCommission && q.Type != domain.NegotiatedConnectionFee && q.Type != domain.NegotiatedFbaFee {
		errs["type"] = "COMMISSION, CONNECTION_FEE or FBA_FEE"
	}
	if q.Status != "" && !slices.Contains(domain.NegotiatedStatuses, q.Status) {
		errs["status"] = "SCHEDULED, ACTIVE, ENDED or EXPIRED"
	}
	if q.Page < 0 {
		errs["page"] = "0 or more"
	}
	if q.Size == 0 {
		q.Size = 25
	}
	if q.Size < 1 || q.Size > 100 {
		errs["size"] = "1 to 100"
	}
	if len(errs) > 0 {
		return domain.NegotiatedRatePage{}, domain.ValidationProblem(errs)
	}
	sc, err := s.r.require(ctx, c, feesModule, domain.LevelView)
	if err != nil {
		return domain.NegotiatedRatePage{}, err
	}
	query := url.Values{"limit": {"500"}}
	if org := strings.TrimSpace(q.OrgID); org != "" {
		query.Set("orgId", org)
	}
	if q.Type != "" {
		query.Set("type", q.Type)
	}
	if q.Status != "" {
		query.Set("status", q.Status)
	}
	var list []port.NegotiatedRateResponse
	if err := sc.get(ctx, sc.up.Payments, "/v1/negotiated-rates", query, &list); err != nil {
		return domain.NegotiatedRatePage{}, err
	}
	ids := []string{}
	for _, n := range list {
		ids = append(ids, n.OrgID)
	}
	names := s.namesOf(ctx, sc, ids)
	canConfigure := canConfigureFees(sc.access)
	needle := strings.ToLower(strings.TrimSpace(q.Q))
	rows := []domain.NegotiatedRateView{}
	for _, n := range list {
		v := s.negotiatedView(n, names, canConfigure)
		if needle != "" {
			hay := strings.ToLower(strings.Join([]string{v.OrgID, deref(v.OrgName, ""), v.Reason, deref(v.Reference, ""), v.NegotiatedBy, v.Category}, " "))
			if !strings.Contains(hay, needle) {
				continue
			}
		}
		rows = append(rows, v)
	}
	out := domain.NegotiatedRatePage{Items: []domain.NegotiatedRateView{}, Total: len(rows), Page: q.Page, Size: q.Size, CanConfigure: canConfigure}
	from := min(q.Page*q.Size, len(rows))
	out.Items = append(out.Items, rows[from:min(from+q.Size, len(rows))]...)
	return out, nil
}

func (s *staffFeesImpl) CreateNegotiatedRate(ctx context.Context, c *domain.Caller, d domain.NegotiatedRateDraft) (domain.NegotiatedRateView, error) {
	sc, err := s.requireConfigure(ctx, c)
	if err != nil {
		return domain.NegotiatedRateView{}, err
	}
	if errs := domain.ValidateNegotiatedRateDraft(d, sc.now, s.retail, domain.DefaultFeeLimits); len(errs) > 0 {
		return domain.NegotiatedRateView{}, domain.ValidationProblem(errs)
	}
	body := jobj{"orgId": strings.TrimSpace(d.OrgID), "type": d.Type, "rateBps": 0, "reason": strings.TrimSpace(d.Reason)}
	if d.Type == domain.NegotiatedFbaFee {
		body["amountMinor"] = *d.AmountMinor // one fee per unit for every FBA line: no category
	} else {
		body["rateBps"] = *d.RateBps
	}
	if d.Type != domain.NegotiatedFbaFee && d.Category != nil && domain.NormalizeFeeCategory(*d.Category) != "" {
		body["category"] = domain.NormalizeFeeCategory(*d.Category)
	}
	if d.StartsAt != nil && d.StartsAt.After(sc.now) {
		body["startsAt"] = d.StartsAt.UTC().Format(time.RFC3339)
	}
	if d.EndsAt != nil {
		body["endsAt"] = d.EndsAt.UTC().Format(time.RFC3339)
	}
	if d.NegotiatedBy != nil && strings.TrimSpace(*d.NegotiatedBy) != "" {
		body["negotiatedBy"] = strings.TrimSpace(*d.NegotiatedBy)
	}
	if d.Reference != nil && strings.TrimSpace(*d.Reference) != "" {
		body["reference"] = strings.TrimSpace(*d.Reference)
	}
	var created port.NegotiatedRateResponse
	if err := sc.post(ctx, sc.up.Payments, "/v1/negotiated-rates", body, &created); err != nil {
		return domain.NegotiatedRateView{}, err
	}
	return s.negotiatedView(created, s.namesOf(ctx, sc, []string{created.OrgID}), true), nil
}

func (s *staffFeesImpl) EndNegotiatedRate(ctx context.Context, c *domain.Caller, id, reason string) (domain.NegotiatedRateView, error) {
	reason = strings.TrimSpace(reason)
	if n := len([]rune(reason)); n < 3 || n > 500 {
		return domain.NegotiatedRateView{}, domain.ValidationProblem(map[string]string{"reason": "Say why the rate ends (3 to 500 characters)"})
	}
	if id == "" || len(id) > 64 {
		return domain.NegotiatedRateView{}, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such negotiated rate.")
	}
	sc, err := s.requireConfigure(ctx, c)
	if err != nil {
		return domain.NegotiatedRateView{}, err
	}
	var ended port.NegotiatedRateResponse
	if err := sc.post(ctx, sc.up.Payments, "/v1/negotiated-rates/"+url.PathEscape(id)+"/end", jobj{"reason": reason}, &ended); err != nil {
		return domain.NegotiatedRateView{}, err
	}
	return s.negotiatedView(ended, s.namesOf(ctx, sc, []string{ended.OrgID}), true), nil
}

// ---------------------------------------------------------------- rate calculator

func (s *staffFeesImpl) Calculate(ctx context.Context, c *domain.Caller, orgID, category, kind string) (domain.FeeRateQuote, error) {
	errs := map[string]string{}
	orgID, category, kind = strings.TrimSpace(orgID), domain.NormalizeFeeCategory(category), strings.ToUpper(strings.TrimSpace(kind))
	if orgID == "" || len(orgID) > 80 {
		errs["orgId"] = "Choose an organisation"
	}
	if category == "" || len(category) > 80 {
		errs["category"] = "Choose a category"
	}
	if kind == "" {
		kind = domain.FeeKindProduct
	}
	if kind != domain.FeeKindProduct && kind != domain.FeeKindService {
		errs["kind"] = "PRODUCT or SERVICE"
	}
	if len(errs) > 0 {
		return domain.FeeRateQuote{}, domain.ValidationProblem(errs)
	}
	sc, err := s.r.require(ctx, c, feesModule, domain.LevelView)
	if err != nil {
		return domain.FeeRateQuote{}, err
	}
	var e port.EffectiveFeesResponse
	if err := sc.get(ctx, sc.up.Payments, "/v1/fee-schedules/effective", url.Values{"orgId": {orgID}, "category": {category}, "kind": {kind}}, &e); err != nil {
		return domain.FeeRateQuote{}, err
	}
	out := domain.FeeRateQuote{OrgID: orgID, Kind: kind, Category: category, CategoryLabel: domain.FeeCategoryLabel(category), Bps: e.CommissionBps,
		Source: strings.ToUpper(firstNonEmpty(e.CommissionSource, domain.RateSourceDefault)), PlanCode: strPtr(e.PlanCode), PlanName: planNameOf(e.PlanCode),
		NegotiatedRateID: strPtr(e.NegotiatedRateID), NegotiatedUntil: e.NegotiatedUntil, Version: e.Version, ExampleSaleMinor: feeExampleSaleMinor,
		ExampleFeeMinor: domain.PercentOf(feeExampleSaleMinor, e.CommissionBps)}
	if name, ok := s.namesOf(ctx, sc, []string{orgID})[orgID]; ok {
		out.OrgName = &name
	}
	out.Explanation = domain.RateExplanation(kind, out.Source, out.PlanName, out.NegotiatedUntil)
	return out, nil
}

// ---------------------------------------------------------------- negotiated plan prices

func (s *staffFeesImpl) NegotiatePlan(ctx context.Context, c *domain.Caller, orgID string, d domain.NegotiatedPlanDraft) (domain.NegotiatedPlan, error) {
	orgID = strings.TrimSpace(orgID)
	errs := domain.ValidateNegotiatedPlanDraft(d, domain.DefaultFeeLimits)
	if orgID == "" || len(orgID) > 80 {
		errs["orgId"] = "Choose an organisation"
	} else if s.retail != "" && orgID == s.retail {
		errs["orgId"] = "AfroTransact Retail is never billed for a plan"
	}
	if len(errs) > 0 {
		return domain.NegotiatedPlan{}, domain.ValidationProblem(errs)
	}
	sc, err := s.r.require(ctx, c, domain.PlansModule, domain.LevelEdit)
	if err != nil {
		return domain.NegotiatedPlan{}, err
	}
	if !sc.access.Can(domain.PermPlansManage) {
		return domain.NegotiatedPlan{}, needsPermission(domain.PermPlansManage)
	}
	base := strings.ToLower(strings.TrimSpace(d.BasePlanCode))
	var plan jobj
	if err := sc.get(ctx, sc.up.Seller, "/v1/billing/plans/"+url.PathEscape(base), nil, &plan); err != nil {
		return domain.NegotiatedPlan{}, err
	}
	orgName := firstNonEmpty(s.namesOf(ctx, sc, []string{orgID})[orgID], orgID)
	code := domain.NegotiatedPlanCode(base, orgID)
	name := firstNonEmpty(jstr(plan, "name"), domain.PlanName(base)) + " · " + orgName
	monthly := *d.MonthlyCents
	quarterly := monthly * 3 * 95 / 100 // the catalogue's default: 3 months less 5 %
	if d.QuarterlyCents != nil {
		quarterly = *d.QuarterlyCents
	}
	annual := monthly * 10 // 2 months free
	if d.AnnualCents != nil {
		annual = *d.AnnualCents
	}
	note := strings.TrimSpace(d.Reason)
	if d.Reference != nil && strings.TrimSpace(*d.Reference) != "" {
		note += " (ref " + strings.TrimSpace(*d.Reference) + ")"
	}
	features := plan["features"]
	if features == nil {
		features = []any{}
	}
	limits := plan["limits"]
	if limits == nil {
		limits = jobj{}
	}
	trial := 0
	if v, ok := plan["trialDays"].(float64); ok {
		trial = int(v)
	}
	sortOrder := 1000
	if v, ok := plan["sortOrder"].(float64); ok {
		sortOrder = int(v) + 1
	}
	create := jobj{"code": code, "kind": firstNonEmpty(jstr(plan, "kind"), "SELLER"), "name": name,
		"tagline": "Negotiated for " + orgName, "features": features, "limits": limits, "visibility": "INVITE_ONLY", "sortOrder": sortOrder,
		"prices": jobj{"monthlyCents": monthly, "quarterlyCents": quarterly, "annualCents": annual, "trialDays": trial,
			"note": "Negotiated price: " + note}}
	sc.idem = "negotiated-plan-" + code + "-" + strconv.FormatInt(monthly, 10)
	var created jobj
	if err := sc.post(ctx, sc.up.Seller, "/v1/billing/plans", create, &created); err != nil {
		return domain.NegotiatedPlan{}, err
	}
	if err := sc.call(ctx, sc.up.Seller, port.StaffCall{Method: http.MethodPut, Path: "/v1/billing/plans/" + url.PathEscape(code) + "/invites",
		Body: jobj{"orgIds": []string{orgID}}}, nil); err != nil {
		return domain.NegotiatedPlan{}, err
	}
	published := sc.post(ctx, sc.up.Seller, "/v1/billing/plans/"+url.PathEscape(code)+"/publish", nil, nil) == nil
	out := domain.NegotiatedPlan{PlanCode: code, PlanName: name, BasePlanCode: base, OrgID: orgID, Visibility: "INVITE_ONLY", Published: published,
		MonthlyCents: monthly, QuarterlyCents: quarterly, AnnualCents: annual,
		Note: fmt.Sprintf("%s can now choose %s in Plan & billing; it stays on its current plan until it does.", orgName, name)}
	if !published {
		out.Note = fmt.Sprintf("%s was saved as a draft and %s is invited; publish it in Plans & billing › Catalogue.", name, orgName)
	}
	return out, nil
}
