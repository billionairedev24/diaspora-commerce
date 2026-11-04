package app

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Delivery fees (ADR 0005 §3) are part of the staff console's Pre-orders module and Settings: one rule per delivery
// method and currency that delivery-runs resolves for window checkout, runs and carrier shipping. Reading needs the
// module's View level; changing a rule needs Edit with preorders:manage, a reason (logged with who and when) and, as
// everywhere in this module, the version the staff member saw (If-Match).
const deliveryFeesPath = "/v1/delivery-fees"

// DeliveryFees lists every rule (one per method and currency).
func (s *staffPreordersImpl) DeliveryFees(ctx context.Context, c *domain.Caller) (any, error) {
	sc, can, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	var out any
	if err := sc.get(ctx, sc.up.DeliveryRuns, deliveryFeesPath, nil, &out); err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

// UpdateDeliveryFee sets the numbers of one rule ({bands} | {handlingMinor, fallbackMinor, freeAboveMinor} |
// {feeMinor}, plus {reason}); the answer is the rule with its new version as the ETag.
func (s *staffPreordersImpl) UpdateDeliveryFee(ctx context.Context, c *domain.Caller, method, currency, ifMatch string, body map[string]any) (any, error) {
	m, okMethod := domain.ValidFeeMethod(method)
	cur, okCurrency := domain.ValidFeeCurrency(currency)
	if !okMethod || !okCurrency {
		return nil, domain.FeeNotFound()
	}
	sent, err := domain.DeliveryFeeRuleBody(m, body)
	if err != nil {
		// A missing reason is reported with the numbers' problems, so the form shows everything at once.
		var p, rp *domain.Problem
		if errors.As(err, &p) && p.Errors != nil {
			if _, rerr := reasonOf(body); errors.As(rerr, &rp) {
				for k, v := range rp.Errors {
					p.Errors[k] = v
				}
			}
		}
		return nil, err
	}
	reason, err := reasonOf(body)
	if err != nil {
		return nil, err
	}
	sent["reason"] = reason
	sc, can, err := s.need(ctx, c, poManage)
	if err != nil {
		return nil, err
	}
	out, err := sc.send(ctx, http.MethodPut, deliveryFeesPath+"/"+m+"/"+cur, ifMatch, sent)
	if err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

// DeliveryFeeChanges is the audit trail, newest first: who changed which rule, when and why.
func (s *staffPreordersImpl) DeliveryFeeChanges(ctx context.Context, c *domain.Caller, q url.Values) (any, error) {
	pq, err := passQuery(q, "method", "currency", "limit")
	if err != nil {
		return nil, err
	}
	problems := map[string]string{}
	if v := pq.Get("method"); v != "" {
		if m, ok := domain.ValidFeeMethod(v); ok {
			pq.Set("method", m)
		} else {
			problems["method"] = "Choose AFROTRANSACT_DELIVERY, CARRIER or PICKUP"
		}
	}
	if v := pq.Get("currency"); v != "" {
		if cur, ok := domain.ValidFeeCurrency(v); ok {
			pq.Set("currency", cur)
		} else {
			problems["currency"] = "Use a three-letter currency code"
		}
	}
	if v := pq.Get("limit"); v != "" && !wholeBetween(v, 1, 200) {
		problems["limit"] = "Between 1 and 200"
	}
	if len(problems) > 0 {
		return nil, domain.ValidationProblem(problems)
	}
	sc, _, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	var out any
	if err := sc.get(ctx, sc.up.DeliveryRuns, deliveryFeesPath+"/changes", pq, &out); err != nil {
		return nil, err
	}
	return items(out), nil
}

// PreviewDeliveryFee answers "what would a customer pay?" with the resolution checkout runs ({method, currency,
// distanceMiles | windowId, carrierQuoteMinor, itemsMinor}); applies is false when no rule can price it.
func (s *staffPreordersImpl) PreviewDeliveryFee(ctx context.Context, c *domain.Caller, body map[string]any) (any, error) {
	problems := map[string]string{}
	sent := map[string]any{}
	method, _ := body["method"].(string)
	if m, ok := domain.ValidFeeMethod(method); ok {
		sent["method"] = m
	} else {
		problems["method"] = "Choose AFROTRANSACT_DELIVERY, CARRIER or PICKUP"
	}
	currency, _ := body["currency"].(string)
	if cur, ok := domain.ValidFeeCurrency(currency); ok {
		sent["currency"] = cur
	} else {
		problems["currency"] = "Use a three-letter currency code"
	}
	if v, ok := body["distanceMiles"]; ok && v != nil {
		f, isNum := v.(float64)
		if !isNum || f < 0 || f > domain.MaxDeliveryFeeMiles {
			problems["distanceMiles"] = "Enter a distance between 0 and 500 miles"
		} else {
			sent["distanceMiles"] = f
		}
	}
	if v, ok := body["windowId"].(string); ok && strings.TrimSpace(v) != "" {
		if domain.ValidPreorderID(strings.TrimSpace(v)) != nil {
			problems["windowId"] = "Choose one of the pre-orders"
		} else {
			sent["windowId"] = strings.TrimSpace(v)
		}
	}
	for _, key := range []string{"carrierQuoteMinor", "itemsMinor"} {
		if v, ok := body[key]; ok && v != nil {
			f, isNum := v.(float64)
			if !isNum || f < 0 || f != float64(int64(f)) || f > 1e9 {
				problems[key] = "Enter an amount of 0 or more"
			} else {
				sent[key] = int64(f)
			}
		}
	}
	if len(problems) > 0 {
		return nil, domain.ValidationProblem(problems)
	}
	sc, _, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	out, err := sc.send(ctx, http.MethodPost, deliveryFeesPath+"/preview", "", sent)
	if err != nil {
		return nil, err
	}
	if out == nil {
		out = map[string]any{"applies": false}
	}
	return out, nil
}

// wholeBetween reports whether s is a whole number in [lo, hi].
func wholeBetween(s string, lo, hi int) bool {
	n := 0
	if s == "" {
		return false
	}
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
		n = n*10 + int(r-'0')
		if n > hi {
			return false
		}
	}
	return n >= lo
}
