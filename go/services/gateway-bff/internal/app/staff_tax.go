package app

import (
	"context"
	"net/http"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"sync"
	"unicode/utf8"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// StaffTaxService serves the category tax tools of Fees & commissions → Sales tax (docs/engineering/category-tax-design.md,
// section 9): the 73 category profiles, the review queue and decisions, the provider code maps, the tax method with its
// SHADOW provider and the shadow report. Everything is read from tax-returns (contracts/openapi/tax-returns.yaml) and
// shown in plain words with people and sellers by name. Rows of a profile are never edited here: rules change through
// the dataset and a seed migration. Every change needs a reason of at least ten characters and is audited upstream with
// the staff member's token.
type StaffTaxService interface {
	Profiles(ctx context.Context, c *domain.Caller, q url.Values) (any, error)
	Profile(ctx context.Context, c *domain.Caller, code string) (any, error)
	ChangeProfileStatus(ctx context.Context, c *domain.Caller, code string, body map[string]any) (any, error)
	Reviews(ctx context.Context, c *domain.Caller, q url.Values) (any, error)
	// LiveWithoutDecision lists live listings that have no resolved tax decision (always empty; the banner counts them).
	LiveWithoutDecision(ctx context.Context, c *domain.Caller, q url.Values) (any, error)
	Review(ctx context.Context, c *domain.Caller, atin string) (any, error)
	ApproveReview(ctx context.Context, c *domain.Caller, atin string, body map[string]any) (any, error)
	ReclassifyReview(ctx context.Context, c *domain.Caller, atin string, body map[string]any) (any, error)
	RequestInformation(ctx context.Context, c *domain.Caller, atin string, body map[string]any) (any, error)
	CodeMap(ctx context.Context, c *domain.Caller, q url.Values) (any, error)
	UpdateCode(ctx context.Context, c *domain.Caller, internalCode string, body map[string]any) (any, error)
	ExportCodeMap(ctx context.Context, c *domain.Caller, provider string) (any, error)
	ImportCodeMap(ctx context.Context, c *domain.Caller, body map[string]any) (any, error)
	Method(ctx context.Context, c *domain.Caller) (any, error)
	ChangeMethod(ctx context.Context, c *domain.Caller, body map[string]any) (any, error)
	ReviewSettings(ctx context.Context, c *domain.Caller) (any, error)
	ChangeReviewSettings(ctx context.Context, c *domain.Caller, body map[string]any) (any, error)
	Shadow(ctx context.Context, c *domain.Caller, q url.Values) (any, error)
}

type staffTaxImpl struct{ r *staffResolver }

var _ StaffTaxService = (*staffTaxImpl)(nil)

// NewStaffTaxService returns the category tax tools use case.
func NewStaffTaxService(d StaffDeps) StaffTaxService { return &staffTaxImpl{r: newStaffResolver(d)} }

const (
	permTaxRead          = "tax:read"
	permTaxClassifyAdmin = "tax:classify-admin"
	permTaxConfigure     = "tax:configure"

	// taxReasonMin is the shortest reason any tax change may carry.
	taxReasonMin = 10
	taxReasonMax = 1000
	taxMaxPage   = 200
)

// need resolves the staff member for a tax read (the Fees module and tax:read) or, with perm, a change (also perm).
func (s *staffTaxImpl) need(ctx context.Context, c *domain.Caller, perm string) (staffCtx, error) {
	sc, err := s.r.require(ctx, c, feesModule, domain.LevelView)
	if err != nil {
		return sc, err
	}
	if !sc.access.Can(permTaxRead) {
		return sc, needsPermission(permTaxRead)
	}
	if perm != "" && !sc.access.Can(perm) {
		return sc, needsPermission(perm)
	}
	if sc.up.TaxReturns == nil {
		return sc, domain.UpstreamUnavailable("tax-returns")
	}
	return sc, nil
}

// reasonOf checks the reason every change needs.
func taxReason(body map[string]any, field string) (string, error) {
	reason := vs(body, field)
	if n := utf8.RuneCountInString(reason); n < taxReasonMin {
		ask := "Say why in at least " + strconv.Itoa(taxReasonMin) + " characters. The reason is kept with your name."
		if field == "message" {
			ask = "Tell the seller what you need, in at least " + strconv.Itoa(taxReasonMin) + " characters."
		}
		return "", domain.FieldProblem(domain.CodeValidationFailed, "Validation failed", field, ask)
	}
	if utf8.RuneCountInString(reason) > taxReasonMax {
		return "", domain.FieldProblem(domain.CodeValidationFailed, "Validation failed", field, "Keep the reason under "+strconv.Itoa(taxReasonMax)+" characters.")
	}
	return reason, nil
}

// taxErr rewrites tax-returns' refusals in plain words; anything else passes through.
func taxErr(err error) error {
	p, ok := port.AsUpstreamProblem(err)
	if err == nil || !ok {
		return err
	}
	var msg string
	switch p.Code {
	case "tax.classification-not-resolvable":
		msg = "This can't be approved yet: the answers don't give a definite tax decision, or the category isn't cleared for sale. " + p.Detail
	case "tax.profile-conflict-unresolved":
		msg = "This open decision still has no code. Update the rules first, then lift the block."
	case "tax.stripe-not-set-up":
		msg = "Stripe Tax isn't set up yet, so it can't be chosen."
	case "tax.unknown-category":
		msg = "One of the categories isn't a known tax category. Choose categories from the list."
	case "tax.invalid-audit-percent":
		msg = "The audit sample is a share from 0 to 100 percent."
	case "tax.profile-not-found":
		return domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such tax category.")
	case "tax.classification-not-found":
		return domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such product variant in tax review.")
	default:
		return err
	}
	return domain.NewProblem(http.StatusUnprocessableEntity, domain.CodeValidationFailed, "Can't do that", strings.TrimSpace(msg)).WithCause(err)
}

func pageOf(q url.Values, def int) (page, size int, err error) {
	page, perr := intQuery(q.Get("page"), 0)
	size, serr := intQuery(q.Get("size"), def)
	if perr != nil || serr != nil || page < 0 || size < 1 || size > taxMaxPage {
		return 0, 0, domain.ValidationProblem(map[string]string{"page": "Use whole numbers: page from 0, size from 1 to " + strconv.Itoa(taxMaxPage)})
	}
	return page, size, nil
}

func intQuery(s string, def int) (int, error) {
	if s == "" {
		return def, nil
	}
	return strconv.Atoi(s)
}

// slicePage returns one page of an in-memory list.
func slicePage(all []jobj, page, size int) []jobj {
	from := min(page*size, len(all))
	return all[from:min(from+size, len(all))]
}

// sortBy orders rows by `field,asc|desc` keys; known limits the fields.
func sortBy(rows []jobj, raw []string, known ...string) error {
	keys, bad := domain.ParseSort(raw, func(k string) bool { return slices.Contains(known, k) })
	if bad != nil {
		return bad
	}
	slices.SortStableFunc(rows, func(a, b jobj) int {
		for _, k := range keys {
			c := compareField(a[k.Key], b[k.Key])
			if c == 0 {
				continue
			}
			if k.Desc {
				c = -c
			}
			return c
		}
		return 0
	})
	return nil
}

func compareField(a, b any) int {
	fa, aok := a.(float64)
	fb, bok := b.(float64)
	if aok && bok {
		switch {
		case fa < fb:
			return -1
		case fa > fb:
			return 1
		}
		return 0
	}
	return strings.Compare(strings.ToLower(displayString(a)), strings.ToLower(displayString(b)))
}

func displayString(v any) string {
	switch x := v.(type) {
	case string:
		return x
	case []string:
		return strings.Join(x, ", ")
	case nil:
		return ""
	}
	return displayValue(v)
}

func containsFold(hay string, needles ...string) bool {
	hay = strings.ToLower(hay)
	for _, n := range needles {
		if !strings.Contains(hay, n) {
			return false
		}
	}
	return true
}

// ---------------------------------------------------------------- profiles

func (s *staffTaxImpl) profileSummaries(ctx context.Context, sc staffCtx) ([]jobj, error) {
	var raw any
	if err := sc.get(ctx, sc.up.TaxReturns, "/v1/tax/profiles", nil, &raw); err != nil {
		return nil, taxErr(err)
	}
	rows := jpage(raw).items
	// The Stripe codes of each category live on the full profile; read them in parallel unless the list carries them.
	if len(rows) > 0 && rows[0]["stripeCodes"] == nil {
		s.fillStripeCodes(ctx, sc, rows)
	}
	for _, r := range rows {
		decorateProfileRow(r)
	}
	return rows, nil
}

func (s *staffTaxImpl) fillStripeCodes(ctx context.Context, sc staffCtx, rows []jobj) {
	var wg sync.WaitGroup
	sem := make(chan struct{}, namesParallel)
	for _, r := range rows {
		wg.Add(1)
		sem <- struct{}{}
		go func() {
			defer wg.Done()
			defer func() { <-sem }()
			var full jobj
			if err := sc.get(ctx, sc.up.TaxReturns, "/v1/tax/profiles/"+url.PathEscape(jstr(r, "categoryCode")), nil, &full); err == nil {
				r["stripeCodes"] = full["stripeCodes"]
			}
		}()
	}
	wg.Wait()
}

func decorateProfileRow(r jobj) {
	status := jstr(r, "status")
	r["departmentName"] = firstNonEmpty(jstr(r, "departmentName"), taxDepartmentName(jstr(r, "department")))
	r["statusLabel"] = taxGateLabel(status)
	r["statusExplain"] = taxGates[status].explain
	r["stripeCodeList"] = strings.Join(strs(r, "stripeCodes"), ", ")
	if r["stripeCodes"] == nil {
		r["stripeCodes"] = []any{}
	}
	r["defaultOutcomeLabel"] = taxOutcomeHint(jstr(r, "defaultOutcome"))
}

func taxOutcomeHint(o string) string {
	if o == "CONDITIONAL" {
		return "Depends on the answers"
	}
	return taxOutcomeLabel(o)
}

func (s *staffTaxImpl) Profiles(ctx context.Context, c *domain.Caller, q url.Values) (any, error) {
	sc, err := s.need(ctx, c, "")
	if err != nil {
		return nil, err
	}
	page, size, err := pageOf(q, 25)
	if err != nil {
		return nil, err
	}
	all, err := s.profileSummaries(ctx, sc)
	if err != nil {
		return nil, err
	}
	dept, status := strings.TrimSpace(q.Get("department")), strings.ToUpper(strings.TrimSpace(q.Get("status")))
	if status != "" && taxGates[status].label == "" {
		return nil, domain.ValidationProblem(map[string]string{"status": "Choose one of the tax statuses"})
	}
	// Facets count the whole list so the filters always show what is there.
	departments, counts := []jobj{}, map[string]int{}
	seen := map[string]int{}
	for _, r := range all {
		d := jstr(r, "department")
		if i, ok := seen[d]; ok {
			departments[i]["count"] = departments[i]["count"].(int) + 1
		} else {
			seen[d] = len(departments)
			departments = append(departments, jobj{"code": d, "name": jstr(r, "departmentName"), "count": 1})
		}
		counts[jstr(r, "status")]++
	}
	terms := strings.Fields(strings.ToLower(q.Get("q")))
	rows := make([]jobj, 0, len(all))
	for _, r := range all {
		if (dept != "" && jstr(r, "department") != dept) || (status != "" && jstr(r, "status") != status) {
			continue
		}
		if !containsFold(strings.Join([]string{jstr(r, "name"), jstr(r, "departmentName"), jstr(r, "stripeCodeList"), jstr(r, "categoryCode")}, " "), terms...) {
			continue
		}
		rows = append(rows, r)
	}
	if err := sortBy(rows, q["sort"], "name", "departmentName", "statusLabel", "scenarioCount", "ruleVersion"); err != nil {
		return nil, err
	}
	statuses := []jobj{}
	for _, st := range taxGateOrder {
		statuses = append(statuses, jobj{"value": st, "label": taxGates[st].label, "count": counts[st]})
	}
	return jobj{"items": slicePage(rows, page, size), "total": len(rows), "page": page, "size": size, "departments": departments, "statuses": statuses,
		"ruleVersion": firstRuleVersion(all), "canChange": sc.access.Can(permTaxClassifyAdmin)}, nil
}

func firstRuleVersion(rows []jobj) string {
	if len(rows) == 0 {
		return ""
	}
	return jstr(rows[0], "ruleVersion")
}

func (s *staffTaxImpl) loadProfile(ctx context.Context, sc staffCtx, code string) (jobj, error) {
	var p jobj
	if err := sc.get(ctx, sc.up.TaxReturns, "/v1/tax/profiles/"+url.PathEscape(code), nil, &p); err != nil {
		return nil, taxErr(err)
	}
	return p, nil
}

func (s *staffTaxImpl) Profile(ctx context.Context, c *domain.Caller, code string) (any, error) {
	sc, err := s.need(ctx, c, "")
	if err != nil {
		return nil, err
	}
	p, err := s.loadProfile(ctx, sc, code)
	if err != nil {
		return nil, err
	}
	return s.profileView(ctx, sc, p), nil
}

func (s *staffTaxImpl) profileView(ctx context.Context, sc staffCtx, p jobj) jobj {
	decorateProfileRow(p)
	facts := taxFactsOf(p)
	factRows := []jobj{}
	for _, f := range objs(p, "facts") {
		factRows = append(factRows, jobj{"id": jstr(f, "id"), "label": jstr(f, "label"), "kind": jstr(f, "kind"), "unit": jstr(f, "unit"), "help": jstr(f, "help"),
			"owner": jstr(f, "owner"), "ownerLabel": taxOwners[jstr(f, "owner")], "required": boolean(f, "required"), "needed": requiredText(f, facts),
			"evidence": boolean(f, "evidence")})
	}
	rows, notes := []jobj{}, []jobj{}
	for _, r := range objs(p, "decisionTable") {
		rows = append(rows, jobj{"id": jstr(r, "id"), "sentence": describeRow(r, facts), "condition": jstr(r, "condition"), "outcome": jstr(r, "outcome"),
			"outcomeLabel": taxOutcomeLabel(jstr(r, "outcome")), "internalTaxCode": jstr(r, "internalTaxCode"), "stripeCode": jstr(r, "stripeCode"),
			"rateDisplay": jstr(r, "rateDisplay")})
		if n := jstr(r, "note"); n != "" {
			notes = append(notes, jobj{"kind": "RULE", "rowId": jstr(r, "id"), "title": "Rule " + jstr(r, "id") + ": " + jstr(r, "condition"), "text": n})
		}
	}
	for _, r := range objs(p, "statusReasons") {
		notes = append(notes, jobj{"kind": "GATE", "title": taxGateLabel(jstr(r, "status")), "decision": r["decision"], "text": jstr(r, "text")})
	}
	if n := jstr(p, "note"); n != "" {
		notes = append(notes, jobj{"kind": "CATEGORY", "title": "Note on this category", "text": n})
	}
	status := jstr(p, "status")
	canChange := sc.access.Can(permTaxClassifyAdmin)
	actions := profileActions(status, canChange)
	history := objs(p, "history")
	nameAll(ctx, sc, history, personRef("changedBy", "Changed by"))
	nameAll(ctx, sc, []jobj{p}, personRef("changedBy", "Changed by"))
	return jobj{"categoryCode": jstr(p, "categoryCode"), "name": jstr(p, "name"), "department": jstr(p, "department"), "departmentName": jstr(p, "departmentName"),
		"status": status, "statusLabel": taxGateLabel(status), "statusExplain": taxGates[status].explain, "defaultOutcome": jstr(p, "defaultOutcome"),
		"defaultOutcomeLabel": jstr(p, "defaultOutcomeLabel"), "scenarioCount": p["scenarioCount"], "openDecision": p["openDecision"], "ruleVersion": jstr(p, "ruleVersion"),
		"stripeCodes": p["stripeCodes"], "conflictingCodes": taxOrEmpty(p["conflictingCodes"]), "facts": factRows, "decisions": rows, "notes": notes,
		"history": history, "changedByName": jstr(p, "changedByName"), "changedAt": jstr(p, "changedAt"), "canChange": canChange, "actions": actions,
		"readOnlyReason": readOnlyReason(status, canChange)}
}

func taxOrEmpty(v any) any {
	if v == nil {
		return []any{}
	}
	return v
}

// profileActions are the status changes the sheet offers. The engine owns REFINE_CODE: it is lifted per product by
// tax review (a verified provider code on each variant), never for the whole category.
func profileActions(status string, canChange bool) []jobj {
	approve := jobj{"key": "approve", "label": "Mark approved", "status": "ACTIVE", "needsSignOff": true,
		"explain": "Lifts the block after the Texas tax professional's sign-off. Records who approved and the reference, and applies from the next rule version."}
	reopen := jobj{"key": "reopen", "label": "Reopen for approval", "status": "NEEDS_APPROVAL", "needsSignOff": false,
		"explain": "Takes the category back to needing sign-off. Products already live in it leave checkout until they are reviewed again."}
	set := func(a jobj, ok bool, why string) jobj {
		a["available"], a["why"] = ok && canChange, ""
		if !canChange {
			a["why"] = "Needs the " + permTaxClassifyAdmin + " permission."
		} else if !ok {
			a["why"] = why
		}
		return a
	}
	switch status {
	case "NEEDS_APPROVAL", "CONFLICT_OPEN", "BLOCKED_BUNDLE":
		return []jobj{set(approve, true, ""), set(reopen, false, "Already waiting for sign-off.")}
	case "ACTIVE":
		return []jobj{set(approve, false, "Already approved."), set(reopen, true, "")}
	default:
		return []jobj{set(approve, false, "The tax engine owns this gate."), set(reopen, false, "The tax engine owns this gate.")}
	}
}

func readOnlyReason(status string, canChange bool) string {
	switch {
	case status == "REFINE_CODE":
		return "This gate is lifted product by product: tax review verifies the specific provider code on each product, then approves it. It is not changed for the whole category."
	case !canChange:
		return "You can read this profile. Changing it needs the " + permTaxClassifyAdmin + " permission."
	}
	return ""
}

func (s *staffTaxImpl) ChangeProfileStatus(ctx context.Context, c *domain.Caller, code string, body map[string]any) (any, error) {
	sc, err := s.need(ctx, c, permTaxClassifyAdmin)
	if err != nil {
		return nil, err
	}
	status := strings.ToUpper(vs(body, "status"))
	if status != "ACTIVE" && status != "NEEDS_APPROVAL" {
		return nil, domain.FieldProblem(domain.CodeValidationFailed, "Validation failed", "status", "A category can be marked approved or reopened for approval here.")
	}
	reason, err := taxReason(body, "reason")
	if err != nil {
		return nil, err
	}
	p, err := s.loadProfile(ctx, sc, code)
	if err != nil {
		return nil, err
	}
	from := jstr(p, "status")
	if from == "REFINE_CODE" {
		return nil, domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Not changed here", readOnlyReason(from, true))
	}
	out := jobj{"status": status, "reason": reason}
	if status == "ACTIVE" && from != "ACTIVE" {
		approver, ref := vs(body, "approvedBy"), vs(body, "decisionRef")
		fields := map[string]string{}
		if approver == "" {
			fields["approvedBy"] = "Name the tax professional or firm who signed this off."
		}
		if ref == "" {
			fields["decisionRef"] = "Give the reference of the signed-off decision or opinion letter."
		}
		if len(fields) > 0 {
			return nil, domain.ValidationProblem(fields)
		}
		out["approvedBy"], out["decisionRef"] = approver, ref
	}
	var updated jobj
	if err := sc.call(ctx, sc.up.TaxReturns, port.StaffCall{Method: http.MethodPut, Path: "/v1/tax/profiles/" + url.PathEscape(code) + "/status", Body: out}, &updated); err != nil {
		return nil, taxErr(err)
	}
	return s.profileView(ctx, sc, updated), nil
}

// filterState keeps the rows whose state field (default "state") equals want; an empty want keeps all.
func filterState(rows []jobj, want string, key ...string) []jobj {
	if want == "" {
		return rows
	}
	field := "state"
	if len(key) > 0 {
		field = key[0]
	}
	out := make([]jobj, 0, len(rows))
	for _, r := range rows {
		if strings.EqualFold(jstr(r, field), want) {
			out = append(out, r)
		}
	}
	return out
}
