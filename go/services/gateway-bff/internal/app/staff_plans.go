package app

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// StaffPlansService is Plans & billing (S292–S297): the seller service's plan catalogue (versions, entitlements, publish,
// migrations, invites), trial campaigns, reward rules with their preview, the grants log and the
// per-seller billing panel with audited adjustments. The seller service validates and authorises again; the BFF checks
// the module level and the permission each operation needs, so the console can hide what a role can't do, and relays
// the staff member's Idempotency-Key on adjustments.
type StaffPlansService interface {
	// Do runs one operation (see StaffPlanOps) with its path parameters, query and JSON body; the answer is the seller
	// service's JSON.
	Do(ctx context.Context, c *domain.Caller, op string, params map[string]string, q url.Values, body json.RawMessage, idem string) (json.RawMessage, error)
	// SearchOrgs finds organisations for the per-seller panel (2 to 120 characters of the name or the org id).
	SearchOrgs(ctx context.Context, c *domain.Caller, q string) (domain.BillingOrgSearch, error)
}

// StaffPlanOp is one Plans & billing operation: the BFF route, the seller service call, and what it needs.
type StaffPlanOp struct {
	Name       string
	Route      string // BFF method and pattern, e.g. "GET /v1/staff/billing/plans/{code}"
	Method     string // seller method
	Path       string // seller path with {param} placeholders
	Permission string // "" = module VIEW is enough
	Status     int    // success status the BFF answers
	Query      []string
	Body       bool
}

// StaffPlanOps are every Plans & billing operation, in contract order.
var StaffPlanOps = []StaffPlanOp{
	{Name: "plans", Route: "GET /v1/staff/billing/plans", Method: http.MethodGet, Path: "/v1/billing/plans"},
	{Name: "createPlan", Route: "POST /v1/staff/billing/plans", Method: http.MethodPost, Path: "/v1/billing/plans",
		Permission: domain.PermPlansManage, Status: http.StatusCreated, Body: true},
	{Name: "plan", Route: "GET /v1/staff/billing/plans/{code}", Method: http.MethodGet, Path: "/v1/billing/plans/{code}"},
	{Name: "updatePlan", Route: "PUT /v1/staff/billing/plans/{code}", Method: http.MethodPut, Path: "/v1/billing/plans/{code}",
		Permission: domain.PermPlansManage, Body: true},
	{Name: "savePlanDraft", Route: "PUT /v1/staff/billing/plans/{code}/draft", Method: http.MethodPut,
		Path: "/v1/billing/plans/{code}/draft", Permission: domain.PermPlansManage, Body: true},
	{Name: "publishPlan", Route: "POST /v1/staff/billing/plans/{code}/publish", Method: http.MethodPost,
		Path: "/v1/billing/plans/{code}/publish", Permission: domain.PermPlansManage},
	{Name: "retirePlan", Route: "POST /v1/staff/billing/plans/{code}/retire", Method: http.MethodPost,
		Path: "/v1/billing/plans/{code}/retire", Permission: domain.PermPlansManage},
	{Name: "setPlanInvites", Route: "PUT /v1/staff/billing/plans/{code}/invites", Method: http.MethodPut,
		Path: "/v1/billing/plans/{code}/invites", Permission: domain.PermPlansManage, Body: true},
	{Name: "planMigration", Route: "GET /v1/staff/billing/plans/{code}/migration", Method: http.MethodGet,
		Path: "/v1/billing/plans/{code}/migration"},
	{Name: "migratePlan", Route: "POST /v1/staff/billing/plans/{code}/migration", Method: http.MethodPost,
		Path: "/v1/billing/plans/{code}/migration", Permission: domain.PermPlansManage, Body: true},
	{Name: "trialCampaigns", Route: "GET /v1/staff/billing/trial-campaigns", Method: http.MethodGet,
		Path: "/v1/billing/trial-campaigns"},
	{Name: "createTrialCampaign", Route: "POST /v1/staff/billing/trial-campaigns", Method: http.MethodPost,
		Path: "/v1/billing/trial-campaigns", Permission: domain.PermPlansManage, Status: http.StatusCreated, Body: true},
	{Name: "updateTrialCampaign", Route: "PUT /v1/staff/billing/trial-campaigns/{id}", Method: http.MethodPut,
		Path: "/v1/billing/trial-campaigns/{id}", Permission: domain.PermPlansManage, Body: true},
	{Name: "trialCampaignStatus", Route: "POST /v1/staff/billing/trial-campaigns/{id}/status", Method: http.MethodPost,
		Path: "/v1/billing/trial-campaigns/{id}/status", Permission: domain.PermPlansManage, Body: true},
	{Name: "rewardRules", Route: "GET /v1/staff/billing/reward-rules", Method: http.MethodGet, Path: "/v1/billing/reward-rules"},
	{Name: "createRewardRule", Route: "POST /v1/staff/billing/reward-rules", Method: http.MethodPost,
		Path: "/v1/billing/reward-rules", Permission: domain.PermPlansManage, Status: http.StatusCreated, Body: true},
	{Name: "previewRewardRule", Route: "POST /v1/staff/billing/reward-rules/preview", Method: http.MethodPost,
		Path: "/v1/billing/reward-rules/preview", Body: true},
	{Name: "updateRewardRule", Route: "PUT /v1/staff/billing/reward-rules/{id}", Method: http.MethodPut,
		Path: "/v1/billing/reward-rules/{id}", Permission: domain.PermPlansManage, Body: true},
	{Name: "rewardRuleStatus", Route: "POST /v1/staff/billing/reward-rules/{id}/status", Method: http.MethodPost,
		Path: "/v1/billing/reward-rules/{id}/status", Permission: domain.PermPlansManage, Body: true},
	{Name: "grants", Route: "GET /v1/staff/billing/grants", Method: http.MethodGet, Path: "/v1/billing/grants",
		Query: []string{"orgId", "source", "type", "ruleId", "q", "page", "size"}},
	{Name: "reasonCodes", Route: "GET /v1/staff/billing/reason-codes", Method: http.MethodGet, Path: "/v1/billing/reason-codes"},
	{Name: "billingPanel", Route: "GET /v1/staff/billing/orgs/{orgId}", Method: http.MethodGet,
		Path: "/v1/orgs/{orgId}/billing/panel"},
	{Name: "creditOrg", Route: "POST /v1/staff/billing/orgs/{orgId}/credits", Method: http.MethodPost,
		Path: "/v1/orgs/{orgId}/billing/credits", Permission: domain.PermBillingAdjust, Status: http.StatusCreated, Body: true},
	{Name: "giveFreeDays", Route: "POST /v1/staff/billing/orgs/{orgId}/free-days", Method: http.MethodPost,
		Path: "/v1/orgs/{orgId}/billing/free-days", Permission: domain.PermBillingAdjust, Status: http.StatusCreated, Body: true},
	{Name: "giveFreeMonths", Route: "POST /v1/staff/billing/orgs/{orgId}/free-months", Method: http.MethodPost,
		Path: "/v1/orgs/{orgId}/billing/free-months", Permission: domain.PermBillingAdjust, Status: http.StatusCreated, Body: true},
	{Name: "setChargeGate", Route: "PUT /v1/staff/billing/orgs/{orgId}/charge-gate", Method: http.MethodPut,
		Path: "/v1/orgs/{orgId}/billing/charge-gate", Permission: domain.PermBillingAdjust, Status: http.StatusCreated, Body: true},
	{Name: "clearChargeGate", Route: "POST /v1/staff/billing/orgs/{orgId}/charge-gate/clear", Method: http.MethodPost,
		Path: "/v1/orgs/{orgId}/billing/charge-gate/clear", Permission: domain.PermBillingAdjust, Status: http.StatusCreated, Body: true},
	{Name: "overrideTrial", Route: "POST /v1/staff/billing/orgs/{orgId}/trial", Method: http.MethodPost,
		Path: "/v1/orgs/{orgId}/billing/trial", Permission: domain.PermBillingAdjust, Status: http.StatusCreated, Body: true},
	{Name: "waiveInvoice", Route: "POST /v1/staff/billing/orgs/{orgId}/invoices/{invoiceId}/waive", Method: http.MethodPost,
		Path: "/v1/orgs/{orgId}/billing/invoices/{invoiceId}/waive", Permission: domain.PermBillingAdjust,
		Status: http.StatusCreated, Body: true},
	// S298: how each seller pays its plan (staff override with a reason) and the default for new sellers.
	{Name: "overridePlanFeeCollection", Route: "PUT /v1/staff/billing/orgs/{orgId}/plan-fee-collection", Method: http.MethodPut,
		Path: "/v1/orgs/{orgId}/billing/plan-fee-collection/override", Permission: domain.PermBillingAdjust, Body: true},
	{Name: "billingSettings", Route: "GET /v1/staff/billing/settings", Method: http.MethodGet, Path: "/v1/billing/settings"},
	{Name: "updateBillingSettings", Route: "PUT /v1/staff/billing/settings", Method: http.MethodPut, Path: "/v1/billing/settings",
		Permission: domain.PermPlansManage, Body: true},
}

// StaffPlanOpParams are the path parameters an operation's route carries.
func StaffPlanOpParams(op StaffPlanOp) []string {
	var out []string
	rest := op.Route
	for {
		i := strings.Index(rest, "{")
		if i < 0 {
			return out
		}
		j := strings.Index(rest[i:], "}")
		out = append(out, rest[i+1:i+j])
		rest = rest[i+j+1:]
	}
}

type staffPlansImpl struct {
	r   *staffResolver
	ops map[string]StaffPlanOp
}

var _ StaffPlansService = (*staffPlansImpl)(nil)

// NewStaffPlansService returns Plans & billing.
func NewStaffPlansService(d StaffDeps) StaffPlansService {
	ops := make(map[string]StaffPlanOp, len(StaffPlanOps))
	for _, op := range StaffPlanOps {
		ops[op.Name] = op
	}
	return &staffPlansImpl{r: newStaffResolver(d), ops: ops}
}

func (s *staffPlansImpl) Do(ctx context.Context, c *domain.Caller, name string, params map[string]string, q url.Values, body json.RawMessage, idem string) (json.RawMessage, error) {
	op, ok := s.ops[name]
	if !ok {
		return nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "Unknown operation.")
	}
	level := domain.LevelView
	if op.Permission != "" {
		level = domain.LevelEdit
	}
	sc, err := s.r.require(ctx, c, domain.PlansModule, level)
	if err != nil {
		return nil, err
	}
	if op.Permission != "" && !sc.access.Can(op.Permission) {
		return nil, needsPermission(op.Permission)
	}
	path := op.Path
	for _, p := range StaffPlanOpParams(op) {
		v := strings.TrimSpace(params[p])
		if v == "" || len(v) > 120 {
			return nil, domain.ValidationProblem(map[string]string{p: "Required"})
		}
		path = strings.ReplaceAll(path, "{"+p+"}", url.PathEscape(v))
	}
	query := url.Values{}
	for _, k := range op.Query {
		if v := strings.TrimSpace(q.Get(k)); v != "" {
			query.Set(k, v)
		}
	}
	if err := pageParams(query); err != nil {
		return nil, err
	}
	call := port.StaffCall{Method: op.Method, Path: path, Query: query}
	if op.Body {
		if len(body) == 0 {
			body = json.RawMessage("{}")
		}
		call.Body = body
	}
	if op.Method != http.MethodGet {
		call.IdempotencyKey = strings.TrimSpace(idem)
	}
	var out json.RawMessage
	if err := sc.call(ctx, sc.up.Seller, call, &out); err != nil {
		return nil, err
	}
	if len(out) == 0 {
		out = json.RawMessage("{}")
	}
	return out, nil
}

// pageParams checks the grants log's paging (page ≥ 0, 1 ≤ size ≤ 200).
func pageParams(q url.Values) error {
	errs := map[string]string{}
	if v := q.Get("page"); v != "" {
		if n, err := strconv.Atoi(v); err != nil || n < 0 {
			errs["page"] = "A page number from 0"
		}
	}
	if v := q.Get("size"); v != "" {
		if n, err := strconv.Atoi(v); err != nil || n < 1 || n > 200 {
			errs["size"] = "1 to 200 rows"
		}
	}
	if len(errs) > 0 {
		return domain.ValidationProblem(errs)
	}
	return nil
}

func (s *staffPlansImpl) SearchOrgs(ctx context.Context, c *domain.Caller, q string) (domain.BillingOrgSearch, error) {
	q = strings.TrimSpace(q)
	if len([]rune(q)) < 2 || len(q) > 120 {
		return domain.BillingOrgSearch{}, domain.ValidationProblem(map[string]string{"q": "Type 2 to 120 characters"})
	}
	sc, err := s.r.require(ctx, c, domain.PlansModule, domain.LevelView)
	if err != nil {
		return domain.BillingOrgSearch{}, err
	}
	needle := strings.ToLower(q)
	out := domain.BillingOrgSearch{Items: []domain.BillingOrgHit{}}
	for page := range orgDirectoryPages {
		var p jobj
		if err := sc.get(ctx, sc.up.Seller, "/v1/orgs/directory", url.Values{"page": {strconv.Itoa(page)}, "size": {"200"}}, &p); err != nil {
			if len(out.Items) > 0 {
				return out, nil
			}
			return domain.BillingOrgSearch{}, err
		}
		items := objs(p, "items")
		for _, it := range items {
			id, name := jstr(it, "id"), firstNonEmpty(jstr(it, "displayName"), jstr(it, "id"))
			if id == "" || (!strings.Contains(strings.ToLower(name), needle) && !strings.EqualFold(id, q)) {
				continue
			}
			out.Items = append(out.Items, domain.BillingOrgHit{OrgID: id, Name: name, Kind: strings.ToUpper(jstr(it, "kind")),
				Status: strings.ToUpper(jstr(it, "status"))})
			if len(out.Items) >= 20 {
				return out, nil
			}
		}
		if len(items) < 200 {
			break
		}
	}
	return out, nil
}
