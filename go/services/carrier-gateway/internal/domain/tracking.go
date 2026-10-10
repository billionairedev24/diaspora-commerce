package domain

import (
	"crypto/sha256"
	"encoding/hex"
	"sort"
	"strings"
	"time"
)

// ShipmentState is our carrier-neutral shipment state (the transit steps of the orders state machine).
type ShipmentState string

// Shipment states carriers can move. Orders owns placed/confirmed/packed/ready/cancelled.
const (
	StateLabelCreated   ShipmentState = "label_created"
	StatePickedUp       ShipmentState = "picked_up"
	StateInTransit      ShipmentState = "in_transit"
	StateOutForDelivery ShipmentState = "out_for_delivery"
	StateDelivered      ShipmentState = "delivered"
	StateDeliveryFailed ShipmentState = "delivery_failed"
	StateReturned       ShipmentState = "returned"
	StateException      ShipmentState = "exception"
)

// Terminal reports whether no further carrier event may change the state.
func (s ShipmentState) Terminal() bool { return s == StateDelivered || s == StateReturned }

// rank orders the forward path; failure states share the rank of the step they interrupt.
func (s ShipmentState) rank() int {
	switch s {
	case StateLabelCreated:
		return 0
	case StatePickedUp:
		return 1
	case StateInTransit, StateException:
		return 2
	case StateOutForDelivery, StateDeliveryFailed:
		return 3
	case StateDelivered, StateReturned:
		return 4
	default:
		return -1
	}
}

// TrackingEvent is one carrier scan, normalised.
type TrackingEvent struct {
	Carrier        CarrierCode   `json:"carrier"`
	TrackingNumber string        `json:"trackingNumber"`
	EventID        string        `json:"eventId"`
	Code           string        `json:"code"`
	Description    string        `json:"description"`
	State          ShipmentState `json:"state"`
	OccurredAt     time.Time     `json:"occurredAt"`
	Location       string        `json:"location,omitempty"`
	Sequence       int           `json:"sequence"`
}

// EventKey derives a stable event id from the fields a carrier repeats identically in polls and pushes, for
// carriers that do not send their own event id. The same scan seen via webhook and via polling de-duplicates.
func EventKey(parts ...string) string {
	sum := sha256.Sum256([]byte(strings.Join(parts, "|")))
	return hex.EncodeToString(sum[:12])
}

// SortEvents orders events by carrier timestamp, then by the carrier's sequence.
func SortEvents(evs []TrackingEvent) {
	sort.SliceStable(evs, func(i, j int) bool {
		if !evs[i].OccurredAt.Equal(evs[j].OccurredAt) {
			return evs[i].OccurredAt.Before(evs[j].OccurredAt)
		}
		return evs[i].Sequence < evs[j].Sequence
	})
}

// Before reports whether e happened before (at, seq).
func (e TrackingEvent) Before(at time.Time, seq int) bool {
	if !e.OccurredAt.Equal(at) {
		return e.OccurredAt.Before(at)
	}
	return e.Sequence < seq
}

// TrackedShipment is the tracking state we hold for a carrier tracking number.
type TrackedShipment struct {
	Carrier        CarrierCode   `json:"carrier"`
	TrackingNumber string        `json:"trackingNumber"`
	LabelID        string        `json:"labelId,omitempty"`
	Reference      string        `json:"reference,omitempty"`
	State          ShipmentState `json:"state"`
	LastEventAt    time.Time     `json:"lastEventAt"`
	LastSequence   int           `json:"lastSequence"`
	Active         bool          `json:"active"`
}

// Apply folds a new (non-duplicate) event into the tracked state and reports whether the state changed.
//
// Rules: events older than the last applied one are history only (out-of-order delivery never regresses);
// delivered/returned are terminal; a late "picked up"/"label created" never moves a shipment that is
// already further along. Failure and exception states may follow any non-terminal state, and a shipment may
// go back to in_transit after a failed delivery attempt.
func (t TrackedShipment) Apply(ev TrackingEvent) (TrackedShipment, bool) {
	if ev.State == "" || t.State.Terminal() {
		return t, false
	}
	if !t.LastEventAt.IsZero() && ev.Before(t.LastEventAt, t.LastSequence) {
		return t, false
	}
	next := t
	next.LastEventAt, next.LastSequence = ev.OccurredAt, ev.Sequence
	if (ev.State == StateLabelCreated || ev.State == StatePickedUp) && ev.State.rank() < t.State.rank() {
		return next, false
	}
	changed := ev.State != t.State
	next.State = ev.State
	if next.State.Terminal() {
		next.Active = false
	}
	return next, changed
}

// TrackingUpdated is the payload of carrier.tracking.updated (topic at.carrier-shipment.events).
type TrackingUpdated struct {
	Carrier        CarrierCode   `json:"carrier"`
	TrackingNumber string        `json:"trackingNumber"`
	LabelID        string        `json:"labelId,omitempty"`
	Reference      string        `json:"reference,omitempty"`
	State          ShipmentState `json:"state"`
	PreviousState  ShipmentState `json:"previousState,omitempty"`
	StateChanged   bool          `json:"stateChanged"`
	Event          TrackingEvent `json:"event"`
}

// Event types and aggregate published by carrier-gateway.
const (
	EventTrackingUpdated = "carrier.tracking.updated"
	EventPickupReady     = "carrier.pickup.ready"
	EventAggregate       = "carrier-shipment"
)
