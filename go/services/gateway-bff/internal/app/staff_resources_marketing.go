package app

import (
	"context"
	"net/url"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

func couponsResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
		v := url.Values{"funding": {"PLATFORM"}}
		if q.Status != "" {
			v.Set("status", q.Status)
		}
		var raw []jobj
		if err := sc.get(ctx, sc.up.Offers, "/v1/coupons", v, &raw); err != nil {
			return nil, err
		}
		if q.Q != "" {
			needle := strings.ToUpper(strings.TrimSpace(q.Q))
			filtered := raw[:0]
			for _, c := range raw {
				if strings.Contains(strings.ToUpper(jstr(c, "code")), needle) {
					filtered = append(filtered, c)
				}
			}
			raw = filtered
		}
		return raw, nil
	}
	list := listAll(load)
	couponType := domain.StaffOptions("PERCENT", "FIXED", "FREE_SHIPPING")
	couponScope := domain.StaffOptions("CART", "CATEGORY", "PRODUCT", "SELLER", "FULFILLED_BY_AFROTRANSACT")
	return &staffResource{
		key: "coupons", module: "marketing", title: "Platform coupons", statusKey: "status", exportable: true,
		searchPlaceholder: "Coupon code",
		columns: []domain.Column{
			col("code", "Code", "mono", 10), col("description", "Description", "text", 22), col("type", "Type", "status", 10),
			col("value", "Value", "number", 8), col("scope", "Scope", "text", 12), col("status", "Status", "status", 10),
			col("redeemedCount", "Redeemed", "number", 8),
		},
		facets: domain.StaffFacets("ACTIVE", "PAUSED", "EXPIRED"),
		idOf:   func(o jobj) string { return jstr(o, "id") },
		list:   list, get: findIn(list, "id"),
		titleOf:    func(o jobj) string { return jstr(o, "code") },
		subtitleOf: func(o jobj) string { return firstNonEmpty(jstr(o, "description"), jstr(o, "type")) },
		version:    func(o jobj) string { return jstr(o, "version") },
		actions: []staffAction{
			{key: "pause", label: "Pause", tone: "neutral", permission: "coupons:manage", confirm: "Pause this coupon?", when: inState("ACTIVE"),
				run: func(ctx context.Context, sc staffCtx, o jobj, _ jobj) error {
					return sc.post(ctx, sc.up.Offers, "/v1/coupons/"+url.PathEscape(jstr(o, "id"))+"/status", jobj{"status": "PAUSED"}, nil)
				}},
			{key: "activate", label: "Activate", tone: "primary", permission: "coupons:manage", confirm: "Activate this coupon?", when: inState("PAUSED"),
				run: func(ctx context.Context, sc staffCtx, o jobj, _ jobj) error {
					return sc.post(ctx, sc.up.Offers, "/v1/coupons/"+url.PathEscape(jstr(o, "id"))+"/status", jobj{"status": "ACTIVE"}, nil)
				}},
		},
		create: &staffCreate{permission: "coupons:manage",
			form: func(context.Context, staffCtx) domain.Form {
				return domain.Form{Title: "New platform coupon", SubmitLabel: "Create coupon", Fields: []domain.FormField{
					withPattern(withMax(ff("code", "Code", "text", true), 20), `^[A-Za-z0-9]{3,20}$`, "3–20 letters or digits"),
					withMax(ff("description", "Description", "text", false), 300),
					withValue(withOptions(ff("type", "Type", "select", true), couponType), "PERCENT"),
					ff("value", "Value", "number", true),
					withValue(withOptions(ff("scope", "Scope", "select", true), couponScope), "CART"),
					withMax(ff("scopeRef", "Scope reference", "text", false), 80),
					ff("minSpendMinor", "Minimum spend (¢)", "number", false),
					ff("totalLimit", "Total redemptions (0 = unlimited)", "number", false),
				}}
			},
			create: func(ctx context.Context, sc staffCtx, v jobj) (string, error) {
				body := jobj{
					"code": vs(v, "code"), "description": vs(v, "description"), "type": vs(v, "type"), "value": vn(v, "value"),
					"currency": "USD", "scope": vs(v, "scope"), "minSpendMinor": vn(v, "minSpendMinor"), "totalLimit": int(vn(v, "totalLimit")),
					"perCustomerLimit": 0, "clipRequired": false, "funding": "PLATFORM",
				}
				if scope := vs(v, "scope"); scope != "CART" {
					body["scopeRef"] = vs(v, "scopeRef")
				}
				var out jobj
				if err := sc.post(ctx, sc.up.Offers, "/v1/coupons", body, &out); err != nil {
					return "", err
				}
				return jstr(out, "id"), nil
			}},
	}
}
