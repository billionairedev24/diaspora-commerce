package app

import (
	"context"
	"net/http"
	"strings"
	"unicode/utf8"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// StaffAISpendService is Settings > AI and MCP spend cap (contracts/openapi/gateway-bff-staff.yaml, tag AI spend): today's spend
// (UTC day) against the daily cap, by feature, read from ai-gateway's usage ledger, and the cap itself. The cap is a runtime
// setting kept by ai-gateway (AI_GLOBAL_DAILY_COST_USD is only its initial default, AI_GLOBAL_DAILY_COST_MAX_USD its hard
// ceiling); the BFF relays reads and changes with the staff member's own token and decides nothing: ai-gateway checks the
// permission, the ceiling, the version and the reason again. Nothing is estimated here: a day without a model call is zero
// with hasData false.
type StaffAISpendService interface {
	// Today needs the ai:manage permission (ai-gateway checks it again with the staff member's own token).
	Today(ctx context.Context, c *domain.Caller) (map[string]any, error)
	// Cap needs ai:manage or ai:cap.manage: the cap in force, its source, the ceiling, today's spend and remaining, and the
	// history of changes with people named. The answer's "version" is the ETag to send back as If-Match.
	Cap(ctx context.Context, c *domain.Caller) (map[string]any, error)
	// SetCap needs ai:cap.manage: {capMicros, reason} with the version loaded as If-Match (428 without it, 412 when stale).
	SetCap(ctx context.Context, c *domain.Caller, ifMatch string, body map[string]any) (map[string]any, error)
}

type staffAISpendImpl struct{ r *staffResolver }

var _ StaffAISpendService = (*staffAISpendImpl)(nil)

// NewStaffAISpendService returns the AI spend use case.
func NewStaffAISpendService(d StaffDeps) StaffAISpendService { return &staffAISpendImpl{r: newStaffResolver(d)} }

func (s *staffAISpendImpl) Today(ctx context.Context, c *domain.Caller) (map[string]any, error) {
	sc, err := s.r.resolve(ctx, c)
	if err != nil {
		return nil, err
	}
	if !sc.access.Can("ai:manage") {
		return nil, needsPermission("ai:manage")
	}
	var res jobj
	if err := sc.get(ctx, sc.up.AI, "/v1/ai/usage/today", nil, &res); err != nil {
		return nil, err
	}
	features := []any{}
	for _, f := range objs(res, "byFeature") {
		features = append(features, jobj{"feature": jstr(f, "feature"), "label": featureLabel(jstr(f, "feature")), "calls": int64(numberOf(f, "calls")),
			"tokens": int64(numberOf(f, "tokens")), "costMicros": int64(numberOf(f, "costMicros"))})
	}
	return jobj{"day": jstr(res, "day"), "capMicros": int64(numberOf(res, "capMicros")), "callerCapMicros": int64(numberOf(res, "callerCapMicros")),
		"spentMicros": int64(numberOf(res, "spentMicros")), "calls": int64(numberOf(res, "calls")), "tokens": int64(numberOf(res, "tokens")),
		"hasData": boolean(res, "hasData"), "resetsAt": jstr(res, "resetsAt"), "byFeature": features}, nil
}

const (
	capManage     = "ai:cap.manage"
	minCapReason  = 3
	maxCapReason  = 300
	capStepMicros = 10_000
)

func (s *staffAISpendImpl) Cap(ctx context.Context, c *domain.Caller) (map[string]any, error) {
	sc, err := s.r.resolve(ctx, c)
	if err != nil {
		return nil, err
	}
	if !sc.access.Can("ai:manage") && !sc.access.Can(capManage) {
		return nil, needsPermission("ai:manage")
	}
	sc.nameWho = true
	var res any
	if err := sc.get(ctx, sc.up.AI, "/v1/ai/spend-cap", nil, &res); err != nil {
		return nil, err
	}
	m, _ := res.(jobj)
	return capView(m), nil
}

func (s *staffAISpendImpl) SetCap(ctx context.Context, c *domain.Caller, ifMatch string, body map[string]any) (map[string]any, error) {
	sc, err := s.r.resolve(ctx, c)
	if err != nil {
		return nil, err
	}
	if !sc.access.Can(capManage) {
		return nil, needsPermission(capManage)
	}
	problems := map[string]string{}
	micros, okMicros := wholeMicros(body["capMicros"])
	switch {
	case !okMicros || micros < 0:
		problems["capMicros"] = "Enter the cap in dollars, 0 or more"
	case micros%capStepMicros != 0:
		problems["capMicros"] = "Use whole cents"
	}
	reason, _ := body["reason"].(string)
	reason = strings.TrimSpace(reason)
	if n := utf8.RuneCountInString(reason); n < minCapReason || n > maxCapReason {
		problems["reason"] = "Say why, 3 to 300 characters: it is logged with your name"
	}
	if len(problems) > 0 {
		return nil, domain.ValidationProblem(problems)
	}
	if strings.TrimSpace(ifMatch) == "" {
		return nil, domain.NewProblem(http.StatusPreconditionRequired, "VERSION_REQUIRED", "Reload first",
			"Reload the page so the change is made against the cap you are looking at.")
	}
	sc.nameWho = true
	var res any
	if err := sc.call(ctx, sc.up.AI, port.StaffCall{Method: http.MethodPut, Path: "/v1/ai/spend-cap", IfMatch: ifMatch,
		Body: jobj{"capMicros": micros, "reason": reason}}, &res); err != nil {
		return nil, err
	}
	m, _ := res.(jobj)
	return capView(m), nil
}

// wholeMicros reads a JSON number that must be a whole number of micro-dollars.
func wholeMicros(v any) (int64, bool) {
	f, ok := v.(float64)
	if !ok || f != float64(int64(f)) {
		return 0, false
	}
	return int64(f), true
}

// capView words ai-gateway's cap document for the console: people are named (never ids) and nothing is derived here.
func capView(res jobj) map[string]any {
	history := []any{}
	for _, h := range objs(res, "history") {
		history = append(history, jobj{"version": int64(numberOf(h, "version")), "previousMicros": nullableMicros(h, "previousMicros"),
			"previousWasDefault": boolean(h, "previousWasDefault"), "newMicros": int64(numberOf(h, "newMicros")),
			"changedByName": personName(jstr(h, "changedBy")), "changedAt": jstr(h, "changedAt"), "reason": jstr(h, "reason")})
	}
	out := jobj{"capMicros": int64(numberOf(res, "capMicros")), "source": jstr(res, "source"), "defaultMicros": int64(numberOf(res, "defaultMicros")),
		"ceilingMicros": int64(numberOf(res, "ceilingMicros")), "version": int64(numberOf(res, "version")), "day": jstr(res, "day"),
		"spentMicros": int64(numberOf(res, "spentMicros")), "remainingMicros": int64(numberOf(res, "remainingMicros")), "resetsAt": jstr(res, "resetsAt"),
		"canChange": boolean(res, "canChange"), "history": history}
	if who := personName(jstr(res, "changedBy")); who != "" {
		out["changedByName"] = who
		out["changedAt"] = jstr(res, "changedAt")
		out["reason"] = jstr(res, "reason")
	}
	return out
}

func nullableMicros(o jobj, key string) any {
	if o[key] == nil {
		return nil
	}
	return int64(numberOf(o, key))
}

// personName is the name nameWho put in a who-field; an id nobody could name is never shown.
func personName(v string) string {
	if v == "" || uuidLike(v) {
		return ""
	}
	return v
}

// featureLabel words an ai-gateway feature key for people: "listing-writer" is "Listing writer".
func featureLabel(key string) string {
	words := strings.Fields(strings.NewReplacer("-", " ", "_", " ", ".", " ").Replace(key))
	if len(words) == 0 {
		return key
	}
	words[0] = strings.ToUpper(words[0][:1]) + words[0][1:]
	return strings.Join(words, " ")
}
