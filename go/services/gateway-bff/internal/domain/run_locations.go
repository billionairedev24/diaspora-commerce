package domain

import (
	"fmt"
	"net/url"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"
)

// Delivery-run locations from the staff console (S262): the towns delivery runs serve. delivery-runs owns them; the BFF
// checks the shape of every body so the console gets field-level answers. A location is never deleted: it is switched
// off with a reason (audited with who and when) and back on.
var (
	slugRe       = regexp.MustCompile(`^[a-z0-9]+(-[a-z0-9]+)*$`)
	areaKinds    = map[string]bool{"POLYGON": true, "RADIUS": true}
	nonSlugRunes = regexp.MustCompile(`[^a-z0-9]+`)
)

// MaxPolygonGeoJSON is the longest polygon (GeoJSON text) a location accepts.
const MaxPolygonGeoJSON = 200_000

// RunLocationSlug derives a slug from a name and state ("Bastrop", "TX" gives bastrop-tx).
func RunLocationSlug(name, state string) string {
	s := nonSlugRunes.ReplaceAllString(strings.ToLower(strings.TrimSpace(name+" "+state)), "-")
	return strings.Trim(s, "-")
}

// locText reads an optional or required text field; ok is false when it is absent, blank (and optional) or invalid, and
// an invalid one is recorded in problems.
func locText(body map[string]any, key string, max int, required bool, label string, problems map[string]string) (string, bool) {
	v, present := body[key]
	if !present || v == nil {
		if required {
			problems[key] = label + " is required"
		}
		return "", false
	}
	s, isText := v.(string)
	s = strings.TrimSpace(s)
	switch {
	case !isText:
		problems[key] = "Write " + strings.ToLower(label) + " as text"
	case s == "":
		if required {
			problems[key] = label + " is required"
		}
	case utf8.RuneCountInString(s) > max:
		problems[key] = fmt.Sprintf("At most %d characters", max)
	default:
		return s, true
	}
	return "", false
}

// RunLocationBody checks what staff enter for a location (create, or replace its details): the name and market, the
// time zone, where it delivers (an area registered as a zone in geo-service, a GeoJSON outline, or a radius in miles
// around the town's centre), the defaults for new runs (fee, minimum, capacity, cutoff policy), and the community
// (WhatsApp) link. An edit never switches the location on or off; that is Activate or Deactivate with a reason.
func RunLocationBody(body map[string]any, create bool) (map[string]any, error) {
	problems := map[string]string{}
	out := map[string]any{}
	name, okName := locText(body, "name", 120, true, "Name", problems)
	market, okMarket := locText(body, "market", 16, true, "Market", problems)
	state, okState := locText(body, "state", 8, false, "State", problems)
	if okName {
		out["name"] = name
	}
	if okMarket {
		out["market"] = strings.ToUpper(market)
	}
	if okState {
		out["state"] = strings.ToUpper(state)
	}
	if slug, ok := locText(body, "slug", 80, false, "Slug", problems); ok {
		if !slugRe.MatchString(slug) {
			problems["slug"] = "Use lower-case letters, digits and hyphens, such as bastrop-tx"
		} else {
			out["slug"] = slug
		}
	} else if _, bad := problems["slug"]; !bad && okName && create {
		// A new town's address is made from its name unless staff typed one. A change keeps the address it has (the
		// caller reads it from delivery-runs): renaming a town must not move its page.
		if derived := RunLocationSlug(name, state); derived != "" {
			out["slug"] = derived
		} else {
			problems["slug"] = "Give the name some letters so a web address can be made from it"
		}
	}
	if tz, ok := locText(body, "timeZone", 64, false, "Time zone", problems); ok {
		if _, err := time.LoadLocation(tz); err != nil || strings.EqualFold(tz, "local") {
			problems["timeZone"] = "Use an IANA time zone such as America/Chicago"
		} else {
			out["timeZone"] = tz
		}
	}
	if lat, ok := numberIn(body["centerLat"], -90, 90); ok {
		out["centerLat"] = lat
	} else {
		problems["centerLat"] = "Enter a latitude between -90 and 90"
	}
	if lng, ok := numberIn(body["centerLng"], -180, 180); ok {
		out["centerLng"] = lng
	} else {
		problems["centerLng"] = "Enter a longitude between -180 and 180"
	}
	locationArea(body, problems, out)
	for _, c := range []struct {
		key    string
		bound  float64
		prompt string
	}{{"originLat", 90, "Enter a latitude between -90 and 90"}, {"originLng", 180, "Enter a longitude between -180 and 180"}} {
		if v, ok := body[c.key]; ok && v != nil {
			if n, ok := numberIn(v, -c.bound, c.bound); ok {
				out[c.key] = n
			} else {
				problems[c.key] = c.prompt
			}
		}
	}
	optionalFee(body, "defaultFeeMinor", problems, out)
	if cur, ok := locText(body, "currency", 3, false, "Currency", problems); ok {
		if cur = strings.ToUpper(cur); !currencyRe.MatchString(cur) {
			problems["currency"] = "Use a three-letter currency code such as USD"
		} else {
			out["currency"] = cur
		}
	}
	minOrders, hasMin := boundedCount(body, "defaultMinOrders", 1, MaxRunOrders, problems, out)
	capOrders, hasCap := boundedCount(body, "defaultCapacityOrders", 1, MaxRunOrders, problems, out)
	boundedCount(body, "defaultCapacityPackages", 1, MaxRunPackages, problems, out)
	boundedCount(body, "defaultExtendDays", 1, 30, problems, out)
	if hasMin && hasCap && capOrders < minOrders {
		problems["defaultCapacityOrders"] = "Capacity cannot be below the minimum orders"
	}
	if v, ok := body["dropMode"]; ok && v != nil {
		s, _ := v.(string)
		if s = strings.ToUpper(strings.TrimSpace(s)); !dropModes[s] {
			problems["dropMode"] = "Choose DOORSTEP or PICKUP_POINT"
		} else {
			out["dropMode"] = s
		}
	}
	if v, ok := body["defaultCutoffPolicy"]; ok && v != nil {
		s, _ := v.(string)
		if s = strings.ToUpper(strings.TrimSpace(s)); !cutoffPolicies[s] {
			problems["defaultCutoffPolicy"] = "Choose EXTEND (once) or CANCEL"
		} else {
			out["defaultCutoffPolicy"] = s
		}
	}
	if _, present := body["whatsappInviteUrl"]; present {
		if link, ok := locText(body, "whatsappInviteUrl", 500, false, "Community link", problems); ok {
			if u, err := url.Parse(link); err != nil || u.Host == "" || (u.Scheme != "https" && u.Scheme != "http") {
				problems["whatsappInviteUrl"] = "Paste the full link, starting with https://"
			} else {
				out["whatsappInviteUrl"] = link
			}
		} else if _, bad := problems["whatsappInviteUrl"]; !bad {
			// A blank link clears it.
			out["whatsappInviteUrl"] = nil
		}
	}
	if active, ok := body["active"].(bool); ok && create {
		out["active"] = active
	}
	if len(problems) > 0 {
		return nil, ValidationProblem(problems)
	}
	return out, nil
}

// locationArea checks where a location delivers.
func locationArea(body map[string]any, problems map[string]string, out map[string]any) {
	kind, _ := body["areaKind"].(string)
	kind = strings.ToUpper(strings.TrimSpace(kind))
	switch {
	case !areaKinds[kind]:
		problems["areaKind"] = "Choose a radius around the town or a drawn area"
	case kind == "RADIUS":
		out["areaKind"] = kind
		if r, ok := numberIn(body["radiusMiles"], 0, 100); !ok || r <= 0 {
			problems["radiusMiles"] = "Enter a radius between 0 and 100 miles"
		} else {
			out["radiusMiles"] = r
		}
	default:
		out["areaKind"] = kind
		zone, hasZone := locText(body, "geoZoneId", 80, false, "Area", problems)
		poly, hasPoly := locText(body, "polygonGeoJson", MaxPolygonGeoJSON, false, "Area outline", problems)
		switch {
		case hasZone && hasPoly:
			problems["polygonGeoJson"] = "Use an existing area or draw a new outline, not both"
		case hasZone:
			out["geoZoneId"] = zone
		case hasPoly:
			out["polygonGeoJson"] = poly
		case problems["geoZoneId"] == "" && problems["polygonGeoJson"] == "":
			problems["geoZoneId"] = "Choose the area the location delivers to, or draw its outline"
		}
	}
}

// RunLocationSwitchBody checks the reason a location is switched off (required) or on (optional); it is audited with
// the staff member's name and the time.
func RunLocationSwitchBody(body map[string]any, required bool) (map[string]any, error) {
	if !required {
		if r, _ := body["reason"].(string); strings.TrimSpace(r) == "" {
			return map[string]any{}, nil
		}
	}
	reason, problem := RunReason(body, "Say why: the reason is logged with your name")
	if problem != "" {
		return nil, ValidationProblem(map[string]string{"reason": problem})
	}
	return map[string]any{"reason": reason}, nil
}
