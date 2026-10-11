package app

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"sort"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ---------------------------------------------------------------- customer pickup points (carrier-gateway)
//
// Delivery dispatch › Pickup points: the AfroTransact counters and lockers where customers collect platform-fulfilled
// packages (AfroTransact Retail and Fulfilled by AfroTransact). Checkout offers the active ones near the delivery
// address; sellers' own pickup locations (kind SELLER) are listed read-only and managed in Seller Center.
// carrier-gateway checks shipping:admin again.

const (
	pickupAdminPermission = "shipping:admin"
	// platformPickupOwner owns AfroTransact's counters and lockers in carrier-gateway.
	platformPickupOwner = "afrotransact"
)

// pickupDays are the opening-day patterns the form offers (carrier-gateway weekdays, 0 = Sunday).
var pickupDays = []struct {
	key, label string
	days       []int
}{
	{"MON_SAT", "Monday to Saturday", []int{1, 2, 3, 4, 5, 6}},
	{"MON_FRI", "Monday to Friday", []int{1, 2, 3, 4, 5}},
	{"EVERY_DAY", "Every day", []int{0, 1, 2, 3, 4, 5, 6}},
	{"TUE_SUN", "Tuesday to Sunday", []int{0, 2, 3, 4, 5, 6}},
}

var pickupZones = []string{"America/New_York", "America/Chicago", "America/Denver", "America/Phoenix", "America/Los_Angeles", "America/Anchorage", "Pacific/Honolulu"}

// pickupHoursBody turns the form's days, opens and closes into carrier-gateway hours.
func pickupHoursBody(days, opens, closes string) []jobj {
	out := []jobj{}
	for _, d := range pickupDays {
		if d.key == days {
			for _, w := range d.days {
				out = append(out, jobj{"weekday": w, "open": opens, "close": closes})
			}
		}
	}
	return out
}

// pickupDaysOf recognises a location's hours as one of the form's patterns (MON_SAT when they match none).
func pickupDaysOf(o jobj) (key, opens, closes string) {
	hours := objs(o, "hours")
	days := []int{}
	for _, h := range hours {
		days = append(days, int(num(h, "weekday")))
	}
	sort.Ints(days)
	key, opens, closes = "MON_SAT", "09:00", "18:00"
	if len(hours) > 0 {
		opens, closes = jstr(hours[0], "open"), jstr(hours[0], "close")
	}
	for _, d := range pickupDays {
		if fmt.Sprint(d.days) == fmt.Sprint(days) {
			key = d.key
		}
	}
	return key, opens, closes
}

func pickupForm(title, submit string, o jobj) domain.Form {
	days, opens, closes := pickupDaysOf(o)
	dayOpts := []domain.FieldOption{}
	for _, d := range pickupDays {
		dayOpts = append(dayOpts, domain.FieldOption{Value: d.key, Label: d.label})
	}
	zoneOpts := []domain.FieldOption{}
	for _, z := range pickupZones {
		zoneOpts = append(zoneOpts, domain.FieldOption{Value: z, Label: z})
	}
	kind := firstNonEmpty(jstr(o, "kind"), "PLATFORM_COUNTER")
	f := []domain.FormField{
		withValue(withMax(ff("name", "Name customers see", "text", true), 80), jstr(o, "name")),
		withValue(withOptions(ff("kind", "Kind", "select", true), []domain.FieldOption{{Value: "PLATFORM_COUNTER", Label: "Pickup counter"}, {Value: "LOCKER", Label: "Locker"}}), kind),
		withValue(withMax(ff("line1", "Street address", "text", true), 120), jstr(o, "address.line1")),
		withValue(withMax(ff("city", "City", "text", true), 60), jstr(o, "address.city")),
		withValue(withPattern(ff("state", "State", "text", true), `^[A-Z]{2}$`, "Two-letter state code, e.g. TX"), jstr(o, "address.state")),
		withValue(withPattern(ff("postalCode", "ZIP code", "text", true), `^[0-9]{5}$`, "5-digit ZIP"), jstr(o, "address.postalCode")),
		withValue(withOptions(ff("timezone", "Time zone", "select", true), zoneOpts), firstNonEmpty(jstr(o, "timezone"), "America/Chicago")),
		withValue(withOptions(ff("days", "Open on", "select", true), dayOpts), days),
		withValue(withPattern(ff("opens", "Opens (24-hour)", "text", true), `^([01][0-9]|2[0-3]):[0-5][0-9]$`, "HH:MM, e.g. 09:00"), opens),
		withValue(withPattern(ff("closes", "Closes (24-hour)", "text", true), `^([01][0-9]|2[0-3]):[0-5][0-9]$`, "HH:MM, e.g. 18:00"), closes),
		withValue(withRange(ff("readyHours", "Ready after (hours)", "number", false), 168), num(o, "readyHours")),
		withValue(ff("latitude", "Latitude (optional, for the distance at checkout)", "number", false), at(o, "position.lat")),
		withValue(ff("longitude", "Longitude (optional)", "number", false), at(o, "position.lng")),
		withValue(ff("active", "Customers can choose it at checkout", "toggle", false), o == nil || boolean(o, "active")),
	}
	return domain.Form{Title: title, SubmitLabel: submit, Fields: f}
}

// pickupBody is carrier-gateway's PickupLocation from the form.
func pickupBody(v jobj) (jobj, error) {
	if vs(v, "opens") >= vs(v, "closes") {
		return nil, domain.FieldProblem(domain.CodeValidationFailed, "Check the opening hours", "closes", "Closing time must be after opening time.")
	}
	kind := vs(v, "kind")
	if kind != "PLATFORM_COUNTER" && kind != "LOCKER" {
		kind = "PLATFORM_COUNTER"
	}
	body := jobj{"kind": kind, "ownerOrg": platformPickupOwner, "name": vs(v, "name"), "timezone": vs(v, "timezone"),
		"address": jobj{"line1": vs(v, "line1"), "city": vs(v, "city"), "state": strings.ToUpper(vs(v, "state")), "postalCode": vs(v, "postalCode"), "country": "US"},
		"hours":   pickupHoursBody(vs(v, "days"), vs(v, "opens"), vs(v, "closes")), "readyHours": int(vn(v, "readyHours")), "active": vb(v, "active")}
	lat, lng := vn(v, "latitude"), vn(v, "longitude")
	if lat != 0 || lng != 0 {
		body["position"] = jobj{"lat": lat, "lng": lng}
	}
	return body, nil
}

func pickupLocationsResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
		var page jobj
		if err := sc.get(ctx, sc.up.Carrier, "/v1/pickup-locations", url.Values{"includeInactive": {"true"}}, &page); err != nil {
			return nil, err
		}
		out := []jobj{}
		search := strings.ToLower(strings.TrimSpace(q.Q))
		for _, l := range objs(page, "items") {
			l["state"] = "ACTIVE"
			if !boolean(l, "active") {
				l["state"] = "PAUSED"
			}
			if q.Status != "" && q.Status != jstr(l, "state") {
				continue
			}
			hay := strings.ToLower(jstr(l, "name") + " " + jstr(l, "address.city") + " " + jstr(l, "address.postalCode"))
			if search != "" && !strings.Contains(hay, search) {
				continue
			}
			out = append(out, l)
		}
		sort.SliceStable(out, func(i, j int) bool { return jstr(out[i], "kind") < jstr(out[j], "kind") })
		return out, nil
	}
	list := listAll(load)
	kindLabel := map[string]string{"PLATFORM_COUNTER": "AfroTransact counter", "LOCKER": "AfroTransact locker", "SELLER": "Seller location"}
	return &staffResource{
		key: "pickup-locations", module: dispatchModule, title: "Pickup points", statusKey: "state", searchPlaceholder: "Name, city or ZIP",
		columns: []domain.Column{col("name", "Name", "strong", 22), col("kindLabel", "Kind", "text", 14), col("place", "Address", "text", 26),
			col("hoursLabel", "Hours", "text", 18), col("readyHours", "Ready after (h)", "number", 8), col("state", "Status", "status", 10)},
		facets: domain.StaffFacets("ACTIVE", "PAUSED"),
		list:   list, get: findIn(list, "id"),
		cells: func(o jobj) map[string]any {
			days, opens, closes := pickupDaysOf(o)
			label := days
			for _, d := range pickupDays {
				if d.key == days {
					label = d.label
				}
			}
			return map[string]any{"kindLabel": kindLabel[jstr(o, "kind")], "hoursLabel": fmt.Sprintf("%s %s–%s", label, opens, closes),
				"place": strings.TrimSpace(jstr(o, "address.line1") + ", " + jstr(o, "address.city") + ", " + jstr(o, "address.state") + " " + jstr(o, "address.postalCode"))}
		},
		titleOf:    func(o jobj) string { return jstr(o, "name") },
		subtitleOf: func(o jobj) string { return kindLabel[jstr(o, "kind")] + " · " + jstr(o, "address.city") },
		sections:   func(o jobj) []domain.DetailSection { return autoSections(o, "state") },
		edit: &staffEdit{permission: pickupAdminPermission,
			form: func(o jobj) domain.Form { return pickupForm("Edit the pickup point", "Save pickup point", o) },
			save: func(ctx context.Context, sc staffCtx, o jobj, v jobj, _ string) error {
				if jstr(o, "kind") == "SELLER" {
					return domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Managed by the seller",
						"Sellers manage their own pickup locations in Seller Center › Ship-from & pickup.")
				}
				body, err := pickupBody(v)
				if err != nil {
					return err
				}
				return sc.call(ctx, sc.up.Carrier, port.StaffCall{Method: http.MethodPut, Path: "/v1/pickup-locations/" + url.PathEscape(jstr(o, "id")), Body: body}, nil)
			}},
		create: &staffCreate{permission: pickupAdminPermission,
			form: func(context.Context, staffCtx) domain.Form {
				return pickupForm("New pickup point", "Add pickup point", nil)
			},
			create: func(ctx context.Context, sc staffCtx, v jobj) (string, error) {
				body, err := pickupBody(v)
				if err != nil {
					return "", err
				}
				var saved jobj
				if err := sc.post(ctx, sc.up.Carrier, "/v1/pickup-locations", body, &saved); err != nil {
					return "", err
				}
				return jstr(saved, "id"), nil
			}},
	}
}
