package app

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Exchange rates (ADR 0005 §7): customers pay in USD, but stock is sourced and received in the currency actually
// paid. Settings → Exchange rates is the staff-managed table of rates to USD that receiving suggests by default
// (inventory-service /v1/exchange-rates); every change carries a reason and is kept in the audit trail.

const (
	inventoryAdjust  = "inventory:adjust"
	rateIDSeparator  = "_"
	ratePattern      = `^[0-9]+(\.[0-9]{1,10})?$`
	rateHelp         = "US dollars for 1 unit of the currency, e.g. 0.0577 for one Mexican peso. At most 10 decimals."
	rateReasonLength = 500
)

// rateID is the row id of a rate: currency and day.
func rateID(o jobj) string { return jstr(o, "currency") + rateIDSeparator + jstr(o, "rateDate") }

// rateKey splits a row id into currency and day.
func rateKey(id string) (string, string, bool) {
	cur, day, ok := strings.Cut(id, rateIDSeparator)
	return cur, day, ok && cur != "" && day != ""
}

// rateBody is the PUT body: the rate as the exact number the staff member typed.
func rateBody(v jobj) jobj {
	return omitEmpty(jobj{"rateToUsd": json.Number(strconv.FormatFloat(vn(v, "rateToUsd"), 'f', -1, 64)),
		"note": vs(v, "note"), "reason": vs(v, "reason")})
}

func putRate(ctx context.Context, sc staffCtx, currency, day string, v jobj) error {
	return sc.call(ctx, sc.up.Inventory, port.StaffCall{Method: http.MethodPut,
		Path: "/v1/exchange-rates/" + url.PathEscape(currency) + "/" + url.PathEscape(day), Body: rateBody(v)}, nil)
}

func exchangeRatesResource() *staffResource {
	list := listPaged("/v1/exchange-rates", upInventory, func(q staffQuery, v url.Values) {
		if q.Q != "" {
			v.Set("currency", strings.ToUpper(q.Q))
		}
	})
	return &staffResource{
		key: "exchange-rates", module: "inventory", title: "Exchange rates", searchPlaceholder: "Currency, e.g. MXN",
		columns: []domain.Column{col("currency", "Currency", "mono", 12), col("rateDate", "Day", "date", 16), col("rateToUsd", "USD per unit", "number", 18),
			col("note", "Source", "text", 24), col("updatedBy", "Set by", "text", 16), col("updatedAt", "Updated", "datetime", 14)},
		idOf: rateID,
		list: list,
		get: func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
			cur, day, ok := rateKey(id)
			if !ok {
				return nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "We couldn't find that.")
			}
			var raw any
			if err := sc.get(ctx, sc.up.Inventory, "/v1/exchange-rates", url.Values{"currency": {cur}, "size": {"200"}}, &raw); err != nil {
				return nil, err
			}
			for _, it := range jpage(raw).items {
				if jstr(it, "rateDate") == day {
					return it, nil
				}
			}
			return nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "We couldn't find that.")
		},
		titleOf:    func(o jobj) string { return jstr(o, "currency") + " on " + jstr(o, "rateDate") },
		subtitleOf: func(o jobj) string { return "1 " + jstr(o, "currency") + " = " + jstr(o, "rateToUsd") + " USD" },
		version:    func(o jobj) string { return jstr(o, "version") },
		sections: func(o jobj) []domain.DetailSection {
			return []domain.DetailSection{{Title: "Rate", Fields: []domain.DetailField{
				{Label: "Currency", Value: jstr(o, "currency"), Kind: "mono"},
				{Label: "Day", Value: jstr(o, "rateDate"), Kind: "date"},
				{Label: "USD per unit", Value: jstr(o, "rateToUsd"), Kind: "text"},
				{Label: "Source", Value: displayValue(at(o, "note")), Kind: "text"},
				{Label: "Set by", Value: jstr(o, "updatedBy"), Kind: "text"},
				{Label: "Updated", Value: jstr(o, "updatedAt"), Kind: "datetime"}}}}
		},
		activity: func(ctx context.Context, sc staffCtx, o jobj) []domain.ActivityEntry {
			var raw any
			if err := sc.get(ctx, sc.up.Inventory, "/v1/exchange-rates/history", url.Values{"currency": {jstr(o, "currency")}, "size": {"200"}}, &raw); err != nil {
				return nil
			}
			out := []domain.ActivityEntry{}
			for _, c := range jpage(raw).items {
				if jstr(c, "rateDate") != jstr(o, "rateDate") {
					continue
				}
				text := "Set to " + jstr(c, "newRate")
				if old := jstr(c, "oldRate"); old != "" {
					text = "Changed from " + old + " to " + jstr(c, "newRate")
				}
				out = append(out, domain.ActivityEntry{At: timeOf(c, "occurredAt"), Actor: jstr(c, "actor"), Text: text + ": " + jstr(c, "reason")})
			}
			return out
		},
		edit: &staffEdit{permission: inventoryAdjust,
			form: func(o jobj) domain.Form {
				return domain.Form{Title: "Change the rate", SubmitLabel: "Save rate",
					Intro: "Receipts already posted keep the rate they were received at. The change is recorded with your reason.",
					Fields: []domain.FormField{
						withValue(withPattern(ff("rateToUsd", "USD per unit", "number", true), ratePattern, rateHelp), num(o, "rateToUsd")),
						withValue(withMax(ff("note", "Source", "text", false), 200), jstr(o, "note")),
						withMax(ff("reason", "Reason", "text", true), rateReasonLength)}}
			},
			save: func(ctx context.Context, sc staffCtx, o jobj, v jobj, _ string) error {
				return putRate(ctx, sc, jstr(o, "currency"), jstr(o, "rateDate"), v)
			}},
		create: &staffCreate{permission: inventoryAdjust,
			form: func(ctx context.Context, sc staffCtx) domain.Form {
				currency := withMax(ff("currency", "Currency", "text", true), 3)
				var raw any
				if err := sc.get(ctx, sc.up.Inventory, "/v1/currencies", nil, &raw); err == nil {
					opts := []domain.FieldOption{}
					for _, c := range jpage(raw).items {
						if !boolean(c, "base") {
							opts = append(opts, domain.FieldOption{Value: jstr(c, "code"), Label: jstr(c, "code") + " · " + jstr(c, "name")})
						}
					}
					if len(opts) > 0 {
						currency = withOptions(ff("currency", "Currency", "select", true), opts)
					}
				}
				return domain.Form{Title: "Set an exchange rate", SubmitLabel: "Save rate",
					Intro: "The latest rate on or before the receiving day is suggested on receipts. Setting a day that already has a rate replaces it, with your reason on record.",
					Fields: []domain.FormField{currency,
						withValue(ff("rateDate", "Day", "date", true), time.Now().UTC().Format(time.DateOnly)),
						withPattern(ff("rateToUsd", "USD per unit", "number", true), ratePattern, rateHelp),
						withMax(ff("note", "Source", "text", false), 200),
						withMax(ff("reason", "Reason", "text", true), rateReasonLength)}}
			},
			create: func(ctx context.Context, sc staffCtx, v jobj) (string, error) {
				cur, day := strings.ToUpper(vs(v, "currency")), vs(v, "rateDate")
				if err := putRate(ctx, sc, cur, day, v); err != nil {
					return "", err
				}
				return fmt.Sprint(cur, rateIDSeparator, day), nil
			}},
	}
}

// formatPaid shows an amount in minor units of the currency it was paid in: "MXN 120.00", "XOF 6,000".
func formatPaid(minor int64, currency string) string {
	digits := 2
	switch currency {
	case "XOF", "XAF", "JPY", "KRW", "UGX", "RWF", "GNF":
		digits = 0
	}
	sign := ""
	if minor < 0 {
		sign, minor = "-", -minor
	}
	scale := int64(1)
	for i := 0; i < digits; i++ {
		scale *= 10
	}
	whole := strconv.FormatInt(minor/scale, 10)
	var grouped strings.Builder
	for i, ch := range whole {
		if i > 0 && (len(whole)-i)%3 == 0 {
			grouped.WriteByte(',')
		}
		grouped.WriteRune(ch)
	}
	if digits == 0 {
		return currency + " " + sign + grouped.String()
	}
	return fmt.Sprintf("%s %s%s.%0*d", currency, sign, grouped.String(), digits, minor%scale)
}
