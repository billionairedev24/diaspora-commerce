package domain

import (
	"fmt"
	"math"
	"net/http"
	"regexp"
	"strings"
	"time"
)

// Run economics and location advertorials (S263, S265): delivery-runs owns them (contracts/openapi/delivery-runs.yaml,
// tags Economics and Advertorials); the BFF checks the shape before relaying so the staff console gets field-level
// answers and nothing malformed reaches delivery-runs.
const (
	// MaxEconomicsPerHourMinor is the highest driver cost per hour (1,000.00).
	MaxEconomicsPerHourMinor = 100_000
	// MaxEconomicsPerMileMinor is the highest vehicle cost per mile (100.00).
	MaxEconomicsPerMileMinor = 10_000
	// MaxEconomicsMinutes is the longest time any step of the cost model takes.
	MaxEconomicsMinutes = 1_440
	// MaxOrderCount is the largest minimum or expected order count.
	MaxOrderCount = 100_000

	// Advertorial copy limits, as in delivery-runs.
	MaxAdvertorialHeadline = 140
	MaxAdvertorialBody     = 400
	MaxAdvertorialCTA      = 40
)

var (
	uuidRe        = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)
	variantRe     = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9-]{0,7}$`)
	areaKeyRe     = regexp.MustCompile(`^[A-Z]{2}/[A-Z0-9]{1,3}(/[^/]{1,80})?$`)
	runStatusSet  = map[string]bool{"DRAFT": true, "ANNOUNCED": true, "OPEN": true, "EXTENDED": true, "CUT_OFF": true, "CONFIRMED": true, "COLLECTING": true, "OUT_FOR_DELIVERY": true, "COMPLETED": true, "CANCELLED": true}
	advStatusSet  = map[string]bool{"DRAFT": true, "LIVE": true, "PAUSED": true, "ENDED": true}
	advSourceSet  = map[string]bool{"RUN": true, "WINDOW": true}
	dropModes     = map[string]bool{"DOORSTEP": true, "PICKUP_POINT": true}
	advertorialOp = map[string]bool{"publish": true, "pause": true, "end": true}
)

// ValidUUID answers 404 for an id that is not a UUID (locations, runs and advertorials are keyed by UUID).
func ValidUUID(id string) *Problem {
	if !uuidRe.MatchString(id) {
		return NewProblem(http.StatusNotFound, CodeNotFound, "Not found", "Nothing here with that id.")
	}
	return nil
}

// ValidAdvertorialAction reads publish, pause or end.
func ValidAdvertorialAction(a string) (string, bool) {
	a = strings.ToLower(strings.TrimSpace(a))
	return a, advertorialOp[a]
}

// numberIn reads a JSON number in [lo, hi].
func numberIn(v any, lo, hi float64) (float64, bool) {
	f, ok := v.(float64)
	if !ok || math.IsNaN(f) || math.IsInf(f, 0) || f < lo || f > hi {
		return 0, false
	}
	return f, true
}

// intIn reads a whole JSON number in [lo, hi].
func intIn(v any, lo, hi int64) (int64, bool) {
	n, ok := wholeNumber(v)
	return n, ok && n >= lo && n <= hi
}

// EconomicsSettingsBody checks the driver cost model staff save (money in minor units, times in minutes, whether the
// drive back counts) and returns what delivery-runs takes. The reason is checked by the caller.
func EconomicsSettingsBody(body map[string]any) (map[string]any, error) {
	problems := map[string]string{}
	out := map[string]any{}
	money := func(key string, hi int64, label string) {
		n, ok := intIn(body[key], 0, hi)
		if !ok {
			problems[key] = fmt.Sprintf("%s: enter an amount between 0 and %d.00", label, hi/100)
			return
		}
		out[key] = n
	}
	minutes := func(key, label string) {
		f, ok := numberIn(body[key], 0, MaxEconomicsMinutes)
		if !ok {
			problems[key] = fmt.Sprintf("%s: enter minutes between 0 and %d", label, MaxEconomicsMinutes)
			return
		}
		out[key] = f
	}
	money("driverCostPerHourMinor", MaxEconomicsPerHourMinor, "Driver cost per hour")
	money("driverCostPerMileMinor", MaxEconomicsPerMileMinor, "Vehicle cost per mile")
	minutes("collectionMinutes", "Seller sweep and loading")
	minutes("perStopMinutes", "Time per doorstep stop")
	minutes("pickupPointStopMinutes", "Time at a pickup point")
	minutes("handoverMinutesPerOrder", "Handover time per order")
	if b, ok := body["roundTrip"].(bool); ok {
		out["roundTrip"] = b
	} else {
		problems["roundTrip"] = "Say whether the drive back counts"
	}
	if len(problems) > 0 {
		return nil, ValidationProblem(problems)
	}
	return out, nil
}

// optionalCount reads an optional whole number of orders (absent or null is left out).
func optionalCount(body map[string]any, key string, lo int64, problems map[string]string, out map[string]any) {
	v, ok := body[key]
	if !ok || v == nil {
		return
	}
	n, ok := intIn(v, lo, MaxOrderCount)
	if !ok {
		problems[key] = fmt.Sprintf("Enter a whole number from %d to %d", lo, MaxOrderCount)
		return
	}
	out[key] = n
}

// optionalFee reads an optional fee of 0–10000 minor units.
func optionalFee(body map[string]any, key string, problems map[string]string, out map[string]any) {
	v, ok := body[key]
	if !ok || v == nil {
		return
	}
	n, ok := readFee(v)
	if !ok {
		problems[key] = "Enter an amount between 0 and 100.00 (0 is free)"
		return
	}
	out[key] = n
}

// WindowPlanBody checks the break-even question a window wizard asks while staff fill it in: where (targets or a
// location), door and pickup, the window's own fees, the minimum and the orders to show a P&L for.
func WindowPlanBody(body map[string]any) (map[string]any, error) {
	problems := map[string]string{}
	out := map[string]any{}
	if v, ok := body["locationId"].(string); ok && strings.TrimSpace(v) != "" {
		if ValidUUID(strings.TrimSpace(v)) != nil {
			problems["locationId"] = "Choose one of the locations"
		} else {
			out["locationId"] = strings.TrimSpace(v)
		}
	}
	targets := []string{}
	if list, ok := body["targets"].([]any); ok {
		if len(list) > 50 {
			problems["targets"] = "At most 50 places"
		}
		for _, t := range list {
			key, _ := t.(string)
			key = strings.TrimSpace(key)
			if !areaKeyRe.MatchString(key) {
				problems["targets"] = "Use places such as US/TX/Bastrop"
				break
			}
			targets = append(targets, key)
		}
	} else if body["targets"] != nil {
		problems["targets"] = "Use places such as US/TX/Bastrop"
	}
	out["targets"] = targets
	if _, has := out["locationId"]; !has && len(targets) == 0 {
		problems["targets"] = "Add a place or choose a location"
	}
	for _, k := range []string{"door", "pickup"} {
		if v, ok := body[k]; ok && v != nil {
			b, isBool := v.(bool)
			if !isBool {
				problems[k] = "Yes or no"
				continue
			}
			out[k] = b
		}
	}
	optionalFee(body, "doorFeeMinor", problems, out)
	optionalFee(body, "pickupFeeMinor", problems, out)
	optionalCount(body, "minOrders", 0, problems, out)
	optionalCount(body, "expectedOrders", 0, problems, out)
	if len(problems) > 0 {
		return nil, ValidationProblem(problems)
	}
	return out, nil
}

// RunPlanBody checks the planner for a run at a location (fee, minimum, capacity, drop mode, expected orders).
func RunPlanBody(body map[string]any) (map[string]any, error) {
	problems := map[string]string{}
	out := map[string]any{}
	id, _ := body["locationId"].(string)
	if id = strings.TrimSpace(id); id == "" || ValidUUID(id) != nil {
		problems["locationId"] = "Choose one of the locations"
	} else {
		out["locationId"] = id
	}
	optionalFee(body, "feeMinor", problems, out)
	optionalCount(body, "minOrders", 1, problems, out)
	optionalCount(body, "capacityOrders", 1, problems, out)
	optionalCount(body, "expectedOrders", 0, problems, out)
	if v, ok := body["dropMode"]; ok && v != nil {
		s, _ := v.(string)
		if s = strings.ToUpper(strings.TrimSpace(s)); !dropModes[s] {
			problems["dropMode"] = "Choose DOORSTEP or PICKUP_POINT"
		} else {
			out["dropMode"] = s
		}
	}
	if v, ok := body["commissionShareMinor"]; ok && v != nil {
		if n, ok := intIn(v, 0, 1e12); ok {
			out["commissionShareMinor"] = n
		} else {
			problems["commissionShareMinor"] = "Enter an amount of 0 or more"
		}
	}
	if len(problems) > 0 {
		return nil, ValidationProblem(problems)
	}
	return out, nil
}

// RunListQuery checks the filters of the runs list.
func RunListQuery(location, status, limit string) (map[string]string, error) {
	problems := map[string]string{}
	out := map[string]string{}
	if location != "" {
		if ValidUUID(location) != nil {
			problems["locationId"] = "Choose one of the locations"
		} else {
			out["locationId"] = location
		}
	}
	if status != "" {
		parts := strings.Split(status, ",")
		for i, p := range parts {
			p = strings.ToUpper(strings.TrimSpace(p))
			if !runStatusSet[p] {
				problems["status"] = "Use run statuses such as OPEN, CONFIRMED or CANCELLED"
				break
			}
			parts[i] = p
		}
		out["status"] = strings.Join(parts, ",")
	}
	if limit != "" {
		if n, ok := parsePositive(limit, 500); ok {
			out["limit"] = fmt.Sprint(n)
		} else {
			problems["limit"] = "Between 1 and 500"
		}
	}
	if len(problems) > 0 {
		return nil, ValidationProblem(problems)
	}
	return out, nil
}

// AdvertorialListQuery checks the filters of the advertorials list.
func AdvertorialListQuery(source, sourceID, status, limit string) (map[string]string, error) {
	problems := map[string]string{}
	out := map[string]string{}
	if source != "" {
		s := strings.ToUpper(strings.TrimSpace(source))
		if !advSourceSet[s] {
			problems["source"] = "Choose RUN or WINDOW"
		} else {
			out["source"] = s
		}
	}
	if sourceID != "" {
		if ValidPreorderID(sourceID) != nil {
			problems["sourceId"] = "Choose a pre-order or a run"
		} else {
			out["sourceId"] = sourceID
		}
	}
	if status != "" {
		s := strings.ToUpper(strings.TrimSpace(status))
		if !advStatusSet[s] {
			problems["status"] = "Choose DRAFT, LIVE, PAUSED or ENDED"
		} else {
			out["status"] = s
		}
	}
	if limit != "" {
		if n, ok := parsePositive(limit, 200); ok {
			out["limit"] = fmt.Sprint(n)
		} else {
			problems["limit"] = "Between 1 and 200"
		}
	}
	if len(problems) > 0 {
		return nil, ValidationProblem(problems)
	}
	return out, nil
}

func parsePositive(s string, hi int) (int, bool) {
	n := 0
	for _, r := range s {
		if r < '0' || r > '9' {
			return 0, false
		}
		n = n*10 + int(r-'0')
		if n > hi {
			return 0, false
		}
	}
	return n, n >= 1
}

// GenerateAdvertorialBody checks which window or run to draft an advertorial for.
func GenerateAdvertorialBody(body map[string]any) (map[string]any, error) {
	problems := map[string]string{}
	out := map[string]any{}
	source, _ := body["source"].(string)
	if source = strings.ToUpper(strings.TrimSpace(source)); !advSourceSet[source] {
		problems["source"] = "Choose RUN or WINDOW"
	} else {
		out["source"] = source
	}
	id, _ := body["sourceId"].(string)
	if id = strings.TrimSpace(id); ValidPreorderID(id) != nil {
		problems["sourceId"] = "Choose a pre-order or a run"
	} else {
		out["sourceId"] = id
	}
	if len(problems) > 0 {
		return nil, ValidationProblem(problems)
	}
	return out, nil
}

// AdvertorialBody checks what staff write for an advertorial: the words, variant, share, priority and schedule. On
// create the window or run it is for is required.
func AdvertorialBody(body map[string]any, create bool) (map[string]any, error) {
	problems := map[string]string{}
	out := map[string]any{}
	if create {
		if g, err := GenerateAdvertorialBody(body); err != nil {
			if p, ok := err.(*Problem); ok {
				for k, v := range p.Errors {
					problems[k] = v
				}
			}
		} else {
			out["source"], out["sourceId"] = g["source"], g["sourceId"]
		}
	}
	text := func(key string, lo, hi int, label string) {
		s, _ := body[key].(string)
		s = strings.TrimSpace(s)
		if n := len([]rune(s)); n < lo || n > hi {
			if lo == 0 {
				problems[key] = fmt.Sprintf("%s is at most %d characters", label, hi)
			} else {
				problems[key] = fmt.Sprintf("%s is %d to %d characters", label, lo, hi)
			}
			return
		}
		out[key] = s
	}
	text("headline", 1, MaxAdvertorialHeadline, "The headline")
	text("body", 0, MaxAdvertorialBody, "The text")
	text("ctaLabel", 1, MaxAdvertorialCTA, "The button label")
	if v, ok := body["variant"]; ok && v != nil {
		s, _ := v.(string)
		if s = strings.TrimSpace(s); s != "" {
			if !variantRe.MatchString(s) {
				problems["variant"] = "A short label such as A or B (letters and digits, up to 8)"
			} else {
				out["variant"] = s
			}
		}
	}
	if v, ok := body["weight"]; ok && v != nil {
		if n, ok := intIn(v, 1, 100); ok {
			out["weight"] = n
		} else {
			problems["weight"] = "Between 1 and 100"
		}
	}
	if v, ok := body["priority"]; ok && v != nil {
		if n, ok := intIn(v, 0, 1000); ok {
			out["priority"] = n
		} else {
			problems["priority"] = "Between 0 and 1000"
		}
	}
	var starts, ends time.Time
	for _, k := range []string{"startsAt", "endsAt"} {
		v, ok := body[k]
		if !ok || v == nil {
			out[k] = nil
			continue
		}
		s, _ := v.(string)
		t, err := time.Parse(time.RFC3339, strings.TrimSpace(s))
		if err != nil {
			problems[k] = "Use a date and time such as 2026-10-24T09:00:00Z"
			continue
		}
		out[k] = t.UTC().Format(time.RFC3339)
		if k == "startsAt" {
			starts = t
		} else {
			ends = t
		}
	}
	if !starts.IsZero() && !ends.IsZero() && !ends.After(starts) {
		problems["endsAt"] = "It has to end after it starts"
	}
	if len(problems) > 0 {
		return nil, ValidationProblem(problems)
	}
	return out, nil
}
