package app

import (
	"context"
	"net/http"
	"net/url"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ---------------------------------------------------------------- delivery fee (orders: shipping settings)
//
// Settings › Flat delivery fee (listing rules D16, section 3 "Launch shipping"): AfroTransact sets all shipping at launch. ONE
// staff-edited record, owned by orders, is the single source of the fee: this BFF's estimate and orders' charge both read
// it. Viewing needs the Fees & commissions module (orders:shipping.view for the history); changing needs Edit and
// orders:shipping.edit, a reason of at least 10 characters, and the version staff saw. Every change is kept with who made
// it and why.

const (
	shippingSettingsPath = "/v1/shipping-settings/" + domain.Currency
	shippingEditKey      = "orders:shipping.edit"
)

func shippingSettingsResource() *staffResource {
	get := func(ctx context.Context, sc staffCtx, _ string) (jobj, error) {
		var o jobj
		if err := sc.get(ctx, sc.up.Orders, "/v1/public/shipping-settings", url.Values{"currency": {domain.Currency}}, &o); err != nil {
			return nil, err
		}
		o["id"] = "shipping"
		o["name"] = "Flat delivery fee"
		o["flatFeeText"] = moneyText(num(o, "flatFeeMinor"))
		o["freeAboveText"] = "Never free"
		if v, ok := o["freeAboveMinor"].(float64); ok && v > 0 {
			o["freeAboveText"] = "Free from " + moneyText(v)
		}
		o["carrierText"] = "Off"
		if boolean(o, "sellerCarrierShippingEnabled") {
			o["carrierText"] = "On"
		}
		return o, nil
	}
	withHistory := func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
		o, err := get(ctx, sc, id)
		if err != nil {
			return nil, err
		}
		// The history needs orders:shipping.view; without it the sheet still shows the fee in force.
		var raw any
		if err := sc.get(ctx, sc.up.Orders, shippingSettingsPath+"/changes", url.Values{"limit": {"20"}}, &raw); err == nil {
			o["history"] = jpage(raw).items
		}
		return o, nil
	}
	feeChange := func(h jobj) string {
		text := "Delivery fee " + moneyText(num(h, "after.flatFeeMinor"))
		if b := mapOrEmpty(h, "before"); len(b) > 0 {
			text = moneyText(num(b, "flatFeeMinor")) + " → " + moneyText(num(h, "after.flatFeeMinor"))
		}
		return text
	}
	return &staffResource{
		key: "shipping-settings", module: feesModule, title: "Flat delivery fee",
		names: []nameRef{personRef("updatedBy", "Changed by"), personRef("changedBy", "Changed by").inList("history")},
		columns: []domain.Column{col("name", "Setting", "strong", 22), col("flatFeeText", "Delivery fee", "text", 16), col("freeAboveText", "Free delivery", "text", 20),
			col("carrierText", "Seller carrier shipping", "text", 18), col("updatedByName", "Last changed by", "text", 24)},
		list: func(ctx context.Context, sc staffCtx, _ staffQuery) (staffList, error) {
			o, err := get(ctx, sc, "shipping")
			if err != nil {
				return staffList{}, err
			}
			one := int64(1)
			return staffList{items: []jobj{o}, total: &one}, nil
		},
		get:     withHistory,
		idOf:    func(jobj) string { return "shipping" },
		titleOf: func(jobj) string { return "Flat delivery fee" },
		subtitleOf: func(o jobj) string {
			return jstr(o, "flatFeeText") + " · " + jstr(o, "freeAboveText") + " · version " + jstr(o, "version")
		},
		version: func(o jobj) string { return jstr(o, "version") },
		sections: func(o jobj) []domain.DetailSection {
			return []domain.DetailSection{
				{Title: "What shoppers pay", Fields: []domain.DetailField{
					fieldOf("Delivery by AfroTransact", jstr(o, "flatFeeText"), "text"),
					fieldOf("AfroTransact collects from the store and delivers", jstr(o, "flatFeeText")+" (the same fee)", "text"),
					fieldOf("Free delivery", jstr(o, "freeAboveText"), "text"),
					fieldOf("Pickup at an AfroTransact point, collect from the store", "Free", "text"),
					fieldOf("Pre-order deliveries", "The fee set on their own pre-order or run (Settings > Pre-orders)", "text"),
					fieldOf("Address too far for standard delivery", "The shopper asks for a quote; customer care agrees the amount", "text"),
				}},
				{Title: "Seller carrier shipping", Fields: []domain.DetailField{
					fieldOf("Offer seller-shipped carrier options at checkout", jstr(o, "carrierText"), "text"),
					fieldOf("Why it is off", "At launch AfroTransact sets all shipping. Turn it on only when sellers ship themselves.", "text"),
				}},
				{Title: "How it applies", Fields: []domain.DetailField{
					fieldOf("One source", "The estimate in the cart and checkout and the amount charged when the order is placed both read this record.", "text"),
					fieldOf("When a change starts", "Within 15 seconds in the cart and checkout. Orders already placed keep what they were charged.", "text"),
				}},
				{Title: "Last change", Fields: []domain.DetailField{
					fieldOf("Changed by", jstr(o, "updatedByName"), "text"),
					fieldOf("Changed at", jstr(o, "updatedAt"), "datetime"),
					fieldOf("Why", jstr(o, "reason"), "text"),
				}},
			}
		},
		tables: func(o jobj) []domain.DetailTable {
			rows := []jobj{}
			for _, h := range objs(o, "history") {
				rows = append(rows, jobj{"id": jstr(h, "version"), "changedAt": jstr(h, "changedAt"), "changedByName": jstr(h, "changedByName"), "change": feeChange(h), "reason": jstr(h, "reason")})
			}
			return []domain.DetailTable{tableOf("Who changed it and why", rows, []domain.Column{col("changedAt", "When", "datetime", 18), col("changedByName", "Who", "text", 20),
				col("change", "Change", "text", 22), col("reason", "Why", "text", 40)}, "id")}
		},
		edit: &staffEdit{permission: shippingEditKey,
			form: func(o jobj) domain.Form {
				section := func(f domain.FormField, s string) domain.FormField { f.Section = s; return f }
				var free any
				if v, ok := o["freeAboveMinor"].(float64); ok && v > 0 {
					free = v / 100
				}
				fee := withValue(withRange(ff("flatFee", "Delivery fee (dollars)", "number", true), 100), num(o, "flatFeeMinor")/100)
				fee.Help = "One flat fee for Delivery by AfroTransact and for AfroTransact collecting from a store. 0 is free."
				above := withValue(withRange(ff("freeAbove", "Free delivery on packages of (dollars, blank = never free)", "number", false), 100_000), free)
				above.Help = "A package whose items cost this much or more is delivered free."
				carrier := withValue(ff("sellerCarrierShipping", "Offer seller-shipped carrier options at checkout", "toggle", false), boolean(o, "sellerCarrierShippingEnabled"))
				carrier.Help = "Off at launch: AfroTransact sets all shipping. Turn on only when sellers ship themselves."
				return domain.Form{Title: "Change the delivery fee", SubmitLabel: "Save",
					ChangeKeys: []string{"flatFee", "freeAbove", "sellerCarrierShipping"},
					Intro:      "Shoppers see the new fee within 15 seconds and are charged it on new orders; placed orders keep what they paid. Every change is kept with who made it and why.",
					Fields: []domain.FormField{section(fee, "What shoppers pay"), section(above, "What shoppers pay"), section(carrier, "Carrier shipping"),
						section(withMax(withMin(ff("reason", "Why it changes (at least 10 characters)", "textarea", true), 10), 500), "Why")}}
			},
			save: func(ctx context.Context, sc staffCtx, o jobj, v jobj, _ string) error {
				body := jobj{"flatFeeMinor": minorOf(v, "flatFee"), "sellerCarrierShippingEnabled": vb(v, "sellerCarrierShipping"),
					"reason": vs(v, "reason"), "expectedVersion": int64(num(o, "version"))}
				if raw := v["freeAbove"]; raw != nil && raw != "" {
					body["freeAboveMinor"] = minorOf(v, "freeAbove")
				}
				return sc.call(ctx, sc.up.Orders, port.StaffCall{Method: http.MethodPut, Path: shippingSettingsPath, Body: body}, nil)
			}},
	}
}
