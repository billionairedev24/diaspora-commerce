package domain

import (
	"fmt"
	"sort"
	"strings"
)

// LabelKind distinguishes outbound shipping labels from customer return labels.
type LabelKind string

// Label kinds.
const (
	KindOutbound LabelKind = "outbound"
	KindReturn   LabelKind = "return"
)

// RateChoice says which rated service a return label is bought with.
type RateChoice string

// Rate choices.
const (
	// RateCheapest buys the lowest amount (ties: fewer transit days, then carrier and service code).
	RateCheapest RateChoice = "CHEAPEST"
	// RateFastest buys the fewest transit days (quotes without an estimate count as slowest; ties: cheaper).
	RateFastest RateChoice = "FASTEST"
	// RateService buys the carrier and service named in the request (a choice the customer made from /v1/rates).
	RateService RateChoice = "SERVICE"
)

// ParseRateChoice validates a rate choice (case-insensitive, default CHEAPEST).
func ParseRateChoice(s string) (RateChoice, error) {
	switch c := RateChoice(strings.ToUpper(strings.TrimSpace(s))); c {
	case "":
		return RateCheapest, nil
	case RateCheapest, RateFastest, RateService:
		return c, nil
	default:
		return "", fmt.Errorf("%w: rateChoice must be CHEAPEST, FASTEST or SERVICE", ErrInvalid)
	}
}

// ReturnMode is how the customer hands the return over.
type ReturnMode string

// Return modes.
const (
	// ReturnPrintedLabel is a label the customer prints and sticks on the parcel.
	ReturnPrintedLabel ReturnMode = "LABEL"
	// ReturnBoxFreeQR is a label-free drop-off: the customer shows a QR code at a carrier location, which packs and
	// labels the item ("show this code at any UPS Store").
	ReturnBoxFreeQR ReturnMode = "BOX_FREE_QR"
)

// ParseReturnMode validates a return mode (case-insensitive, default LABEL).
func ParseReturnMode(s string) (ReturnMode, error) {
	switch m := ReturnMode(strings.ToUpper(strings.TrimSpace(s))); m {
	case "":
		return ReturnPrintedLabel, nil
	case ReturnPrintedLabel, ReturnBoxFreeQR:
		return m, nil
	default:
		return "", fmt.Errorf("%w: mode must be LABEL or BOX_FREE_QR", ErrInvalid)
	}
}

// ChooseQuote picks the quote a rate choice asks for. It never mutates quotes.
func ChooseQuote(quotes []Quote, choice RateChoice) (Quote, error) {
	if len(quotes) == 0 {
		return Quote{}, fmt.Errorf("%w: no rated service to choose from", ErrCarrierRejected)
	}
	sorted := make([]Quote, len(quotes))
	copy(sorted, quotes)
	transit := func(q Quote) int {
		if q.TransitDays <= 0 {
			return 1 << 30
		}
		return q.TransitDays
	}
	tie := func(a, b Quote) bool {
		if a.Carrier != b.Carrier {
			return a.Carrier < b.Carrier
		}
		return a.Service < b.Service
	}
	switch choice {
	case RateCheapest:
		sort.SliceStable(sorted, func(i, j int) bool {
			a, b := sorted[i], sorted[j]
			if a.AmountMinor != b.AmountMinor {
				return a.AmountMinor < b.AmountMinor
			}
			if transit(a) != transit(b) {
				return transit(a) < transit(b)
			}
			return tie(a, b)
		})
	case RateFastest:
		sort.SliceStable(sorted, func(i, j int) bool {
			a, b := sorted[i], sorted[j]
			if transit(a) != transit(b) {
				return transit(a) < transit(b)
			}
			if a.AmountMinor != b.AmountMinor {
				return a.AmountMinor < b.AmountMinor
			}
			return tie(a, b)
		})
	default:
		return Quote{}, fmt.Errorf("%w: %s is not chosen from quotes", ErrInvalid, choice)
	}
	return sorted[0], nil
}
