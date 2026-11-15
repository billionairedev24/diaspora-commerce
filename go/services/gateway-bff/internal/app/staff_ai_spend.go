package app

import (
	"context"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// StaffAISpendService is the live half of Settings > AI and MCP spend cap (contracts/openapi/gateway-bff-staff.yaml, tag AI spend):
// today's spend (UTC day) against the daily cap, by feature, read from ai-gateway's usage ledger. The cap is deployment
// configuration (AI_GLOBAL_DAILY_COST_USD) and cannot be changed here. Nothing is estimated in the BFF: a day without a model
// call is zero with hasData false.
type StaffAISpendService interface {
	// Today needs the ai:manage permission (ai-gateway checks it again with the staff member's own token).
	Today(ctx context.Context, c *domain.Caller) (map[string]any, error)
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
		"hasData": boolean(res, "hasData"), "resetsAt": jstr(res, "resetsAt"), "capSetBy": "AI_GLOBAL_DAILY_COST_USD", "byFeature": features}, nil
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
