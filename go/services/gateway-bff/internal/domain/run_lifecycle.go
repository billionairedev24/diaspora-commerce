package domain

import (
	"fmt"
	"strings"
	"time"
	"unicode/utf8"
)

// Delivery runs from the staff console (S260): delivery-runs owns the lifecycle; the BFF checks the shape of every
// body before relaying it so the console gets field-level answers and nothing malformed reaches delivery-runs.
const (
	// MaxRunOrders is the largest minimum or order capacity of a run.
	MaxRunOrders = 10_000
	// MaxRunPackages is the largest package capacity of a run.
	MaxRunPackages = 100_000
	// MaxRunNotes is the longest note on a run.
	MaxRunNotes = 500
	// MaxRunWindow is the longest delivery window.
	MaxRunWindow = 24 * time.Hour
)

var (
	cutoffPolicies = map[string]bool{"EXTEND": true, "CANCEL": true}
	runOrderStatus = map[string]bool{"RESERVED": true, "CONFIRMED": true, "RELEASED": true, "CANCELLED": true}
)

// RunAllowed says what a run's status permits. It mirrors delivery-runs' lifecycle (DRAFT, ANNOUNCED, OPEN, EXTENDED,
// CUT_OFF, CONFIRMED, COLLECTING, OUT_FOR_DELIVERY, COMPLETED, CANCELLED); the staff member's own access is separate.
type RunAllowed struct {
	// Edit: a draft or announced run changes freely, an open one keeps its fee, cutoff and drop mode.
	Edit bool `json:"edit"`
	// Delete: only a draft.
	Delete bool `json:"delete"`
	// Announce: a draft.
	Announce bool `json:"announce"`
	// Open: a draft or announced run.
	Open bool `json:"open"`
	// Extend: an open, extended or cut-off run moves its cutoff later.
	Extend bool `json:"extend"`
	// Confirm: an open, extended or cut-off run is confirmed now (below its minimum only with a reason).
	Confirm bool `json:"confirm"`
	// Cancel: any run up to confirmed; every order is refunded.
	Cancel bool `json:"cancel"`
}

// RunActions derives what a run in this status can do.
func RunActions(status string) RunAllowed {
	switch strings.ToUpper(strings.TrimSpace(status)) {
	case "DRAFT":
		return RunAllowed{Edit: true, Delete: true, Announce: true, Open: true, Cancel: true}
	case "ANNOUNCED":
		return RunAllowed{Edit: true, Open: true, Cancel: true}
	case "OPEN", "EXTENDED":
		return RunAllowed{Edit: true, Extend: true, Confirm: true, Cancel: true}
	case "CUT_OFF":
		return RunAllowed{Extend: true, Confirm: true, Cancel: true}
	case "CONFIRMED":
		return RunAllowed{Cancel: true}
	}
	return RunAllowed{}
}

// runInstant reads a required or optional RFC 3339 instant and returns it in UTC.
func runInstant(body map[string]any, key string, required bool, problems map[string]string) (time.Time, bool) {
	v, present := body[key]
	if !present || v == nil {
		if required {
			problems[key] = "Choose a date and time"
		}
		return time.Time{}, false
	}
	s, _ := v.(string)
	t, err := time.Parse(time.RFC3339, strings.TrimSpace(s))
	if err != nil {
		problems[key] = "Use a date and time such as 2026-10-24T09:00:00Z"
		return time.Time{}, false
	}
	return t.UTC(), true
}

// boundedCount reads an optional whole number in [lo, hi]; absent or null is left out.
func boundedCount(body map[string]any, key string, lo, hi int64, problems map[string]string, out map[string]any) (int64, bool) {
	v, ok := body[key]
	if !ok || v == nil {
		return 0, false
	}
	n, ok := intIn(v, lo, hi)
	if !ok {
		problems[key] = fmt.Sprintf("Enter a whole number from %d to %d", lo, hi)
		return 0, false
	}
	out[key] = n
	return n, true
}

// RunScheduleBody checks what staff schedule for a run (create, or change before it opens) and returns what
// delivery-runs takes: the location (required on create), the delivery window (at most 24 hours), the order cutoff (before
// the window), an optional automatic opening, seller pickup, fee (absent or null: the delivery fee rule prices the
// run), minimum, capacity, drop mode, cutoff policy and a note. Instants are returned in UTC.
func RunScheduleBody(body map[string]any, create bool) (map[string]any, error) {
	problems := map[string]string{}
	out := map[string]any{}
	if v, ok := body["locationId"].(string); ok && strings.TrimSpace(v) != "" {
		if ValidUUID(strings.TrimSpace(v)) != nil {
			problems["locationId"] = "Choose one of the locations"
		} else {
			out["locationId"] = strings.TrimSpace(v)
		}
	} else if create {
		problems["locationId"] = "Choose the location the run goes to"
	}
	start, okStart := runInstant(body, "windowStart", true, problems)
	end, okEnd := runInstant(body, "windowEnd", true, problems)
	cutoff, okCutoff := runInstant(body, "cutoffAt", true, problems)
	opens, okOpens := runInstant(body, "opensAt", false, problems)
	pickup, okPickup := runInstant(body, "sellerPickupAt", false, problems)
	stamp := func(key string, t time.Time) { out[key] = t.Format(time.RFC3339) }
	if okStart {
		stamp("windowStart", start)
	}
	if okEnd {
		stamp("windowEnd", end)
	}
	if okCutoff {
		stamp("cutoffAt", cutoff)
	}
	if okOpens {
		stamp("opensAt", opens)
	}
	if okPickup {
		stamp("sellerPickupAt", pickup)
	}
	if okStart && okEnd {
		switch {
		case !end.After(start):
			problems["windowEnd"] = "The delivery window has to end after it starts"
		case end.Sub(start) > MaxRunWindow:
			problems["windowEnd"] = "A delivery window is at most 24 hours"
		}
	}
	if okStart && okCutoff && !cutoff.Before(start) {
		problems["cutoffAt"] = "Orders have to close before the delivery window starts"
	}
	if okCutoff && okOpens && !opens.Before(cutoff) {
		problems["opensAt"] = "The run has to open before its cutoff"
	}
	if okCutoff && okEnd && okPickup && (pickup.Before(cutoff) || pickup.After(end)) {
		problems["sellerPickupAt"] = "Sellers are collected between the cutoff and the end of the delivery window"
	}
	optionalFee(body, "feeMinor", problems, out)
	minOrders, hasMin := boundedCount(body, "minOrders", 1, MaxRunOrders, problems, out)
	capOrders, hasCap := boundedCount(body, "capacityOrders", 1, MaxRunOrders, problems, out)
	boundedCount(body, "capacityPackages", 1, MaxRunPackages, problems, out)
	boundedCount(body, "extendDays", 1, 30, problems, out)
	if hasMin && hasCap && capOrders < minOrders {
		problems["capacityOrders"] = "Capacity cannot be below the minimum orders"
	}
	if v, ok := body["dropMode"]; ok && v != nil {
		s, _ := v.(string)
		if s = strings.ToUpper(strings.TrimSpace(s)); !dropModes[s] {
			problems["dropMode"] = "Choose DOORSTEP or PICKUP_POINT"
		} else {
			out["dropMode"] = s
		}
	}
	if v, ok := body["cutoffPolicy"]; ok && v != nil {
		s, _ := v.(string)
		if s = strings.ToUpper(strings.TrimSpace(s)); !cutoffPolicies[s] {
			problems["cutoffPolicy"] = "Choose EXTEND (once) or CANCEL"
		} else {
			out["cutoffPolicy"] = s
		}
	}
	if v, ok := body["notes"]; ok && v != nil {
		s, isText := v.(string)
		s = strings.TrimSpace(s)
		switch {
		case !isText:
			problems["notes"] = "Write the note as text"
		case utf8.RuneCountInString(s) > MaxRunNotes:
			problems["notes"] = fmt.Sprintf("At most %d characters", MaxRunNotes)
		default:
			// A blank note clears it.
			out["notes"] = s
		}
	}
	if len(problems) > 0 {
		return nil, ValidationProblem(problems)
	}
	return out, nil
}

// RunExtendBody checks an extension: the new cutoff (and optionally a new window and seller pickup) and the reason,
// which is required because it is logged with who and when.
func RunExtendBody(body map[string]any) (map[string]any, error) {
	problems := map[string]string{}
	out := map[string]any{}
	cutoff, ok := runInstant(body, "cutoffAt", true, problems)
	if ok {
		out["cutoffAt"] = cutoff.Format(time.RFC3339)
	}
	start, okStart := runInstant(body, "windowStart", false, problems)
	end, okEnd := runInstant(body, "windowEnd", false, problems)
	pickup, okPickup := runInstant(body, "sellerPickupAt", false, problems)
	if okStart != okEnd {
		problems["windowEnd"] = "Give both ends of the delivery window, or neither"
	}
	if okStart && okEnd {
		out["windowStart"], out["windowEnd"] = start.Format(time.RFC3339), end.Format(time.RFC3339)
		switch {
		case !end.After(start):
			problems["windowEnd"] = "The delivery window has to end after it starts"
		case end.Sub(start) > MaxRunWindow:
			problems["windowEnd"] = "A delivery window is at most 24 hours"
		}
		if ok && !cutoff.Before(start) {
			problems["cutoffAt"] = "Orders have to close before the delivery window starts"
		}
	}
	if okPickup {
		out["sellerPickupAt"] = pickup.Format(time.RFC3339)
	}
	reason, reasonProblem := RunReason(body, "Say why: the reason is logged with your name")
	if reasonProblem != "" {
		problems["reason"] = reasonProblem
	} else {
		out["reason"] = reason
	}
	if len(problems) > 0 {
		return nil, ValidationProblem(problems)
	}
	return out, nil
}

// RunReason reads the reason of a decision: trimmed, at most 500 characters. The second answer is the message for the
// reason field when it is missing (blank, with missing) or too long.
func RunReason(body map[string]any, missing string) (string, string) {
	reason, _ := body["reason"].(string)
	reason = strings.TrimSpace(reason)
	switch {
	case reason == "":
		return "", missing
	case utf8.RuneCountInString(reason) > 500:
		return "", "At most 500 characters"
	}
	return reason, ""
}

// RunOrdersQuery checks the slot status filter of a run's orders.
func RunOrdersQuery(status string) (string, error) {
	if status == "" {
		return "", nil
	}
	s := strings.ToUpper(strings.TrimSpace(status))
	if !runOrderStatus[s] {
		return "", ValidationProblem(map[string]string{"status": "Use RESERVED, CONFIRMED, RELEASED or CANCELLED"})
	}
	return s, nil
}
