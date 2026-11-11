package app

import (
	"context"
	"math"
	"net/http"
	"slices"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// How tax review works (docs/engineering/listing-rules.md D7). ALL sends every product variant to a tax reviewer and is
// the default. RISK_BASED lets the system approve a variant itself when its category is active, the rules give a clear
// answer, no required proof is missing and the category is not on the always-review list. It must only be switched on
// after the Texas tax professional agrees, so the screen says so in plain words.

const (
	reviewModeAll   = "ALL"
	reviewModeRisk  = "RISK_BASED"
	autoApprovalWho = "Automatic approval (risk-based rule)"
)

var taxReviewModes = map[string]taxLabel{
	reviewModeAll:  {"Review every item", "Every product variant waits for a tax reviewer before it can go live. This is the default and how tax review works today."},
	reviewModeRisk: {"Review by risk", "The system approves an item by itself only when its category is active, the tax rules give a clear answer, no required proof is missing and the category is not on the always-review list. Everything else waits for a tax reviewer. A share of the approved items is also put in the Audit sample to be checked by a person."},
}

const taxReviewWarning = "Only switch to Review by risk after the Texas tax professional has agreed. It lets the system approve items without a person looking at them, and the tax rules are still a draft. " +
	"Items in a category that needs sign-off, needs a specific code, waits on a decision or is a blocked bundle, items in the always-review list, and anything the rules can't decide still wait for a tax reviewer. " +
	"Checkout still blocks any item that has no clear stored tax decision."

func reviewModeName(m string) string {
	if l, ok := taxReviewModes[m]; ok {
		return l.label
	}
	return domain.Humanize(m)
}

// automaticName is shown instead of an id for the system's own decisions ("auto", SYSTEM:...), which no directory knows.
func isSystemActor(id string) bool {
	return id == "auto" || strings.HasPrefix(id, "SYSTEM:")
}

// nameSystemActors puts a plain name on the fields that hold the system as actor and takes the id out, so the name
// lookup does not try to find a user called "auto".
func nameSystemActors(items []jobj, idKeys ...string) {
	for _, it := range items {
		for _, k := range idKeys {
			if id := jstr(it, k); isSystemActor(id) {
				delete(it, k)
				it[nameKeyOf(k)] = autoApprovalWho
			}
		}
	}
}

func (s *staffTaxImpl) reviewSettings(ctx context.Context, sc staffCtx) (jobj, error) {
	var st jobj
	if err := sc.get(ctx, sc.up.TaxReturns, "/v1/tax/review-settings", nil, &st); err != nil {
		return nil, taxErr(err)
	}
	return st, nil
}

func (s *staffTaxImpl) ReviewSettings(ctx context.Context, c *domain.Caller) (any, error) {
	sc, err := s.need(ctx, c, "")
	if err != nil {
		return nil, err
	}
	st, err := s.reviewSettings(ctx, sc)
	if err != nil {
		return nil, err
	}
	return s.reviewSettingsView(ctx, sc, st), nil
}

func reviewStrings(o jobj, key string) []string {
	out := []string{}
	if l, ok := o[key].([]any); ok {
		for _, v := range l {
			if s, ok := v.(string); ok {
				out = append(out, s)
			}
		}
	}
	return out
}

// settingsSummary says what one stored version of the settings meant, in words.
func settingsSummary(v jobj, names map[string]string) string {
	if v == nil {
		return "Not set (every item was reviewed)"
	}
	mode := jstr(v, "mode")
	if mode != reviewModeRisk {
		return reviewModeName(mode)
	}
	always := reviewStrings(v, "alwaysReview")
	listed := make([]string, 0, len(always))
	for _, code := range always {
		listed = append(listed, firstNonEmpty(names[code], domain.Humanize(code)))
	}
	slices.Sort(listed)
	text := reviewModeName(mode) + ", " + strconv.Itoa(int(num(v, "auditPercent"))) + "% audit sample"
	if len(listed) == 0 {
		return text + ", no always-review categories"
	}
	return text + ", always review: " + strings.Join(listed, ", ")
}

func (s *staffTaxImpl) reviewSettingsView(ctx context.Context, sc staffCtx, st jobj) jobj {
	mode := firstNonEmpty(jstr(st, "mode"), reviewModeAll)
	always := reviewStrings(st, "alwaysReview")
	var profiles any
	names := map[string]string{}
	categories := []jobj{}
	alwaysNames := []string{}
	if err := sc.get(ctx, sc.up.TaxReturns, "/v1/tax/profiles", nil, &profiles); err == nil {
		for _, p := range jpage(profiles).items {
			code := jstr(p, "categoryCode")
			status := jstr(p, "status")
			names[code] = jstr(p, "name")
			listed := slices.Contains(always, code)
			if listed {
				alwaysNames = append(alwaysNames, jstr(p, "name"))
			}
			categories = append(categories, jobj{"code": code, "name": jstr(p, "name"), "departmentName": taxDepartmentName(jstr(p, "department")),
				"alwaysReview": listed, "gated": status != "" && status != "ACTIVE", "gateLabel": taxGateLabel(status)})
		}
	}
	slices.Sort(alwaysNames)
	statuses := []jobj{}
	for _, g := range reviewStrings(st, "alwaysReviewStatuses") {
		statuses = append(statuses, jobj{"status": g, "label": taxGateLabel(g)})
	}
	options := []jobj{}
	for _, m := range []string{reviewModeAll, reviewModeRisk} {
		options = append(options, jobj{"mode": m, "label": taxReviewModes[m].label, "explain": taxReviewModes[m].explain, "current": m == mode})
	}
	percent := int(num(st, "auditPercent"))
	history := []jobj{}
	for _, h := range objs(st, "history") {
		from, _ := h["from"].(map[string]any)
		to, _ := h["to"].(map[string]any)
		history = append(history, jobj{"at": jstr(h, "changedAt"), "changedBy": jstr(h, "changedBy"), "fromLabel": settingsSummary(from, names),
			"toLabel": settingsSummary(to, names), "summary": settingsSummary(to, names), "reason": jstr(h, "reason")})
	}
	nameAll(ctx, sc, history, personRef("changedBy", "Changed by"))
	nameAll(ctx, sc, []jobj{st}, personRef("changedBy", "Changed by"))
	return jobj{"mode": mode, "modeLabel": reviewModeName(mode), "modeExplain": taxReviewModes[mode].explain, "options": options, "categories": categories,
		"alwaysReviewNames": alwaysNames, "alwaysReviewStatuses": statuses, "auditPercent": percent,
		"auditExplain": "About " + strconv.Itoa(percent) + " in every 100 items the system approves are also put in the Audit sample. They stay live. A reviewer confirms each one or moves it to the right category, which closes it until it is reviewed again.",
		"history":      history, "changedByName": jstr(st, "changedByName"), "changedAt": jstr(st, "changedAt"), "canChange": sc.access.Can(permTaxClassifyAdmin),
		"warning": taxReviewWarning}
}

func (s *staffTaxImpl) ChangeReviewSettings(ctx context.Context, c *domain.Caller, body map[string]any) (any, error) {
	sc, err := s.need(ctx, c, permTaxClassifyAdmin)
	if err != nil {
		return nil, err
	}
	mode := strings.ToUpper(vs(body, "mode"))
	if _, ok := taxReviewModes[mode]; !ok {
		return nil, domain.FieldProblem(domain.CodeValidationFailed, "Validation failed", "mode", "Choose Review every item or Review by risk.")
	}
	reason, err := taxReason(body, "reason")
	if err != nil {
		return nil, err
	}
	up := jobj{"mode": mode, "reason": reason}
	if v, ok := body["auditPercent"]; ok && v != nil {
		f, isNum := v.(float64)
		if !isNum || f != math.Trunc(f) || f < 0 || f > 100 {
			return nil, domain.FieldProblem(domain.CodeValidationFailed, "Validation failed", "auditPercent", "Use a whole number from 0 to 100.")
		}
		up["auditPercent"] = int(f)
	}
	if v, ok := body["alwaysReview"]; ok && v != nil {
		list, isList := v.([]any)
		if !isList {
			return nil, domain.FieldProblem(domain.CodeValidationFailed, "Validation failed", "alwaysReview", "Choose categories from the list.")
		}
		codes := []string{}
		for _, item := range list {
			code, _ := item.(string)
			if code = strings.TrimSpace(code); code != "" && !slices.Contains(codes, code) {
				codes = append(codes, code)
			}
		}
		up["alwaysReview"] = codes
	}
	var out jobj
	if err := sc.call(ctx, sc.up.TaxReturns, port.StaffCall{Method: http.MethodPut, Path: "/v1/tax/review-settings", Body: up}, &out); err != nil {
		return nil, taxErr(err)
	}
	return s.reviewSettingsView(ctx, sc, out), nil
}
