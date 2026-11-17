package domain

import "strings"

// ShipDetails is how a seller who ships an order themselves says it left: a tracking number (with the courier's name if they
// like) or handover details in words (who took it, a local delivery). One of tracking number or note is required by orders.
type ShipDetails struct {
	Note            string
	TrackingNumber  string
	TrackingCarrier string
}

// Clean trims the fields.
func (d ShipDetails) Clean() ShipDetails {
	return ShipDetails{Note: strings.TrimSpace(d.Note), TrackingNumber: strings.TrimSpace(d.TrackingNumber), TrackingCarrier: strings.TrimSpace(d.TrackingCarrier)}
}

// Validate checks the lengths and characters the same way orders will.
func (d ShipDetails) Validate() error {
	errs := map[string]string{}
	if n := len([]rune(d.Note)); n > 500 {
		errs["shipNote"] = "Up to 500 characters"
	}
	if n := len(d.TrackingNumber); n > 80 || strings.ContainsAny(d.TrackingNumber, " \t\r\n/?#") {
		errs["trackingNumber"] = "Enter the tracking number without spaces (up to 80 characters)"
	}
	if len([]rune(d.TrackingCarrier)) > 40 {
		errs["trackingCarrier"] = "Up to 40 characters"
	}
	if len(errs) > 0 {
		return ValidationProblem(errs)
	}
	return nil
}
