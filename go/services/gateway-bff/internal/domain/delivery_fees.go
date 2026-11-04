package domain

import (
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"regexp"
	"sort"
	"strings"
)

// Delivery fee rules (ADR 0005 §3): staff configure what delivery costs by delivery method, in one currency each.
// delivery-runs owns them (contracts/openapi/delivery-runs.yaml, tag Delivery fees); the BFF checks the shape before
// relaying so the staff console gets field-level answers.
const (
	// FeeMethodAfroTransact is AfroTransact's own delivery inside covered locations: a flat fee by distance band.
	FeeMethodAfroTransact = "AFROTRANSACT_DELIVERY"
	// FeeMethodCarrier ships beyond coverage: the carrier-gateway quote plus handling, or a fallback fee.
	FeeMethodCarrier = "CARRIER"
	// FeeMethodPickup is collected by the customer (often free).
	FeeMethodPickup = "PICKUP"

	// MaxDeliveryFeeMinor is the largest fee of a rule (100.00).
	MaxDeliveryFeeMinor = 10_000
	// MaxDeliveryFeeBands is the most distance bands one rule holds.
	MaxDeliveryFeeBands = 12
	// MaxDeliveryFeeMiles is how far a band may reach.
	MaxDeliveryFeeMiles = 500
)

// FeeMethods lists the delivery methods in screen order.
var FeeMethods = []string{FeeMethodAfroTransact, FeeMethodCarrier, FeeMethodPickup}

var currencyRe = regexp.MustCompile(`^[A-Z]{3}$`)

// ValidFeeMethod reads a delivery method from a path or body; the second result is false for an unknown one.
func ValidFeeMethod(s string) (string, bool) {
	m := strings.ToUpper(strings.TrimSpace(s))
	for _, k := range FeeMethods {
		if m == k {
			return m, true
		}
	}
	return "", false
}

// ValidFeeCurrency reads an ISO 4217 code.
func ValidFeeCurrency(s string) (string, bool) {
	c := strings.ToUpper(strings.TrimSpace(s))
	return c, currencyRe.MatchString(c)
}

// FeeNotFound is the answer for a method or currency that is not one.
func FeeNotFound() *Problem {
	return NewProblem(http.StatusNotFound, CodeNotFound, "Not found", "No such delivery fee.")
}

// DeliveryFeeRuleBody checks the numbers of one rule and returns what delivery-runs takes: only the fields the method
// uses. Problems are keyed by field ("bands[1].toMiles", "fallbackMinor").
//
//	AFROTRANSACT_DELIVERY  bands: 1–12 of {fromMiles, toMiles, feeMinor}, starting at 0 and touching end to start
//	CARRIER                handlingMinor, fallbackMinor (0–10000), freeAboveMinor (null = never free)
//	PICKUP                 feeMinor (0–10000)
func DeliveryFeeRuleBody(method string, body map[string]any) (map[string]any, error) {
	problems := map[string]string{}
	out := map[string]any{}
	switch method {
	case FeeMethodAfroTransact:
		bands := feeBands(body["bands"], problems)
		if len(problems) == 0 {
			out["bands"] = bands
		}
	case FeeMethodCarrier:
		out["handlingMinor"] = feeMinor(body, "handlingMinor", problems)
		out["fallbackMinor"] = feeMinor(body, "fallbackMinor", problems)
		if v, ok := body["freeAboveMinor"]; ok && v != nil {
			n, ok := wholeNumber(v)
			if !ok || n < 0 {
				problems["freeAboveMinor"] = "Enter an amount of 0 or more, or leave it empty"
			} else {
				out["freeAboveMinor"] = n
			}
		}
	case FeeMethodPickup:
		out["feeMinor"] = feeMinor(body, "feeMinor", problems)
	default:
		return nil, FeeNotFound()
	}
	if len(problems) > 0 {
		return nil, ValidationProblem(problems)
	}
	return out, nil
}

// readFee reads a fee of 0–10000 minor units.
func readFee(v any) (int64, bool) {
	n, ok := wholeNumber(v)
	return n, ok && n >= 0 && n <= MaxDeliveryFeeMinor
}

// feeMinor reads the required fee {key} of a rule into its relay body, or reports it under {key}.
func feeMinor(body map[string]any, key string, problems map[string]string) int64 {
	n, ok := readFee(body[key])
	if !ok {
		problems[key] = "Enter an amount between 0 and 100.00 (0 is free)"
		return 0
	}
	return n
}

// wholeNumber reads a JSON number without a fraction.
func wholeNumber(v any) (int64, bool) {
	switch n := v.(type) {
	case float64:
		if n != math.Trunc(n) || math.Abs(n) > 1e15 {
			return 0, false
		}
		return int64(n), true
	case int:
		return int64(n), true
	case int64:
		return n, true
	case json.Number:
		i, err := n.Int64()
		return i, err == nil
	}
	return 0, false
}

// milesOf reads a distance with at most two decimals.
func milesOf(v any) (float64, bool) {
	var f float64
	switch n := v.(type) {
	case float64:
		f = n
	case int:
		f = float64(n)
	case int64:
		f = float64(n)
	case json.Number:
		g, err := n.Float64()
		if err != nil {
			return 0, false
		}
		f = g
	default:
		return 0, false
	}
	if math.IsNaN(f) || math.IsInf(f, 0) || f < 0 || f > MaxDeliveryFeeMiles || math.Abs(f*100-math.Round(f*100)) > 1e-6 {
		return 0, false
	}
	return f, true
}

type feeBand struct {
	from, to float64
	fee      int64
}

// feeBands checks the distance bands: sorted by start, the first at 0 miles, each starting where the last ended.
func feeBands(raw any, problems map[string]string) []map[string]any {
	list, _ := raw.([]any)
	if len(list) == 0 || len(list) > MaxDeliveryFeeBands {
		problems["bands"] = fmt.Sprintf("Add between 1 and %d distance bands", MaxDeliveryFeeBands)
		return nil
	}
	bands := make([]feeBand, 0, len(list))
	for i, item := range list {
		m, _ := item.(map[string]any)
		key := func(f string) string { return fmt.Sprintf("bands[%d].%s", i, f) }
		from, okFrom := milesOf(m["fromMiles"])
		to, okTo := milesOf(m["toMiles"])
		if !okFrom {
			problems[key("fromMiles")] = "Enter a distance in miles (up to 500)"
		}
		if !okTo {
			problems[key("toMiles")] = "Enter a distance in miles (up to 500)"
		} else if okFrom && to <= from {
			problems[key("toMiles")] = "The band must end after it starts"
		}
		fee, okFee := readFee(m["feeMinor"])
		if !okFee {
			problems[key("feeMinor")] = "Enter an amount between 0 and 100.00 (0 is free)"
		}
		bands = append(bands, feeBand{from: from, to: to, fee: fee})
	}
	if len(problems) > 0 {
		return nil
	}
	sort.SliceStable(bands, func(a, b int) bool { return bands[a].from < bands[b].from })
	reached := 0.0
	out := make([]map[string]any, 0, len(bands))
	for i, b := range bands {
		if math.Abs(b.from-reached) > 1e-9 {
			if i == 0 {
				problems["bands[0].fromMiles"] = "The first band starts at 0 miles"
			} else if b.from < reached {
				problems[fmt.Sprintf("bands[%d].fromMiles", i)] = "Bands must not overlap: this one starts before the last ends"
			} else {
				problems[fmt.Sprintf("bands[%d].fromMiles", i)] = fmt.Sprintf("Bands must not leave a gap: start at %s miles", trimMiles(reached))
			}
			return nil
		}
		reached = b.to
		out = append(out, map[string]any{"fromMiles": b.from, "toMiles": b.to, "feeMinor": b.fee})
	}
	return out
}

func trimMiles(f float64) string {
	s := fmt.Sprintf("%.2f", f)
	s = strings.TrimRight(strings.TrimRight(s, "0"), ".")
	return s
}
