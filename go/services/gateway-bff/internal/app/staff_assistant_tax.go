package app

import (
	"context"
	"net/url"
	"strconv"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Lola's read-only tax tools: the review queue and the shadow report, through the console's own use case, so the
// member's module access and tax:read permission apply. Names, never ids, reach her.

func staffToolTaxReviews(ctx context.Context, r *staffRun, a map[string]any) (any, error) {
	if r.s.a.Tax == nil {
		return nil, domain.UpstreamUnavailable("tax-returns")
	}
	limit := argInt(a, "limit", 8, 1, 10)
	q := url.Values{"size": {strconv.Itoa(limit)}}
	if s := argStr(a, "status"); s != "" {
		q.Set("status", s)
	}
	if s := argStr(a, "query"); s != "" {
		q.Set("q", s)
	}
	out, err := r.s.a.Tax.Reviews(ctx, r.c, q)
	if err != nil {
		return nil, err
	}
	page, _ := out.(jobj)
	rows := []map[string]any{}
	for _, it := range objs(page, "items") {
		proposed, _ := it["proposed"].(map[string]any)
		rows = append(rows, map[string]any{"product": jstr(it, "title"), "sellers": it["sellers"], "category": jstr(it, "categoryName"), "status": jstr(it, "statusLabel"),
			"whyItNeedsReview": jstr(it, "why"), "proposedTax": firstNonEmpty(jstr(proposed, "outcomeLabel"), jstr(proposed, "statusLabel")),
			"decidedBy": jstr(it, "reviewedByName"), "decidedAt": jstr(it, "reviewedAt"), "ruleVersion": jstr(it, "ruleVersion")})
	}
	return map[string]any{"showing": jstr(page, "status"), "total": page["total"], "variants": rows}, nil
}

func staffToolTaxShadow(ctx context.Context, r *staffRun, a map[string]any) (any, error) {
	if r.s.a.Tax == nil {
		return nil, domain.UpstreamUnavailable("tax-returns")
	}
	days := argInt(a, "days", 30, 7, 90)
	if days != 7 && days != 30 {
		days = 90
	}
	now := r.sc.now.UTC()
	q := url.Values{"from": {now.AddDate(0, 0, -days).Format(time.DateOnly)}, "to": {now.Format(time.DateOnly)}, "size": {"1"}}
	out, err := r.s.a.Tax.Shadow(ctx, r.c, q)
	if err != nil {
		return nil, err
	}
	d, _ := out.(jobj)
	if boolean(d, "shadowOff") {
		return map[string]any{"shadowMode": "off", "note": jstr(d, "message")}, nil
	}
	checks := []map[string]any{}
	ready, _ := d["readiness"].(jobj)
	for _, c := range objs(ready, "checks") {
		checks = append(checks, map[string]any{"check": jstr(c, "title"), "passes": boolean(c, "ok"), "detail": jstr(c, "detail")})
	}
	cats := []map[string]any{}
	for i, c := range objs(d, "byCategory") {
		if i == 5 {
			break
		}
		cats = append(cats, map[string]any{"category": jstr(c, "label"), "lines": c["lines"], "differing": c["differing"], "differingPercent": c["differingPct"]})
	}
	return map[string]any{"provider": jstr(d, "providerLabel"), "from": jstr(d, "from"), "to": jstr(d, "to"), "linesCompared": d["linesCompared"], "linesDiffering": d["linesDiffering"],
		"readyToCutOver": boolean(ready, "ready"), "summary": jstr(ready, "headline"), "checks": checks, "byCategory": cats}, nil
}
