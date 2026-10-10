package domain_test

import (
	"errors"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
)

func at(h int) time.Time { return time.Date(2026, 10, 10, h, 0, 0, 0, time.UTC) }

func TestApplyNeverRegresses(t *testing.T) {
	ev := func(s domain.ShipmentState, h int) domain.TrackingEvent {
		return domain.TrackingEvent{State: s, OccurredAt: at(h)}
	}
	cases := []struct {
		name    string
		start   domain.TrackedShipment
		ev      domain.TrackingEvent
		want    domain.ShipmentState
		changed bool
	}{
		{"forward", domain.TrackedShipment{State: domain.StateLabelCreated}, ev(domain.StatePickedUp, 1), domain.StatePickedUp, true},
		{"older event ignored", domain.TrackedShipment{State: domain.StateOutForDelivery, LastEventAt: at(5)}, ev(domain.StateInTransit, 3), domain.StateOutForDelivery, false},
		{"late pickup scan does not regress", domain.TrackedShipment{State: domain.StateInTransit, LastEventAt: at(5)}, ev(domain.StatePickedUp, 6), domain.StateInTransit, false},
		{"delivered is terminal", domain.TrackedShipment{State: domain.StateDelivered, LastEventAt: at(5)}, ev(domain.StateInTransit, 9), domain.StateDelivered, false},
		{"returned is terminal", domain.TrackedShipment{State: domain.StateReturned, LastEventAt: at(5)}, ev(domain.StateDelivered, 9), domain.StateReturned, false},
		{"failed attempt then back in transit", domain.TrackedShipment{State: domain.StateDeliveryFailed, LastEventAt: at(5)}, ev(domain.StateInTransit, 7), domain.StateInTransit, true},
		{"exception from any open state", domain.TrackedShipment{State: domain.StateOutForDelivery, LastEventAt: at(5)}, ev(domain.StateException, 6), domain.StateException, true},
		{"unmapped event is history only", domain.TrackedShipment{State: domain.StateInTransit, LastEventAt: at(5)}, ev("", 6), domain.StateInTransit, false},
		{"same state newer event", domain.TrackedShipment{State: domain.StateInTransit, LastEventAt: at(5)}, ev(domain.StateInTransit, 6), domain.StateInTransit, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, changed := tc.start.Apply(tc.ev)
			if got.State != tc.want || changed != tc.changed {
				t.Fatalf("state=%s changed=%v, want %s %v", got.State, changed, tc.want, tc.changed)
			}
		})
	}
	done, _ := domain.TrackedShipment{State: domain.StateOutForDelivery, Active: true}.Apply(ev(domain.StateDelivered, 8))
	if done.Active {
		t.Fatal("terminal state must stop tracking")
	}
}

func TestFingerprintIsStable(t *testing.T) {
	a := domain.Shipment{OriginNode: "fc_houston_tx", From: domain.Address{PostalCode: "77002"}, To: domain.Address{PostalCode: "10001", City: "New York"},
		Parcels: []domain.Parcel{{WeightLb: 1}, {WeightLb: 2}}, ShipDate: at(0)}
	b := a
	b.Parcels = []domain.Parcel{{WeightLb: 2}, {WeightLb: 1}}
	b.To.City = " new york "
	if a.Fingerprint("cart-1") != b.Fingerprint("cart-1") {
		t.Fatal("parcel order and city case must not change the fingerprint")
	}
	if a.Fingerprint("cart-1") == a.Fingerprint("cart-2") {
		t.Fatal("fingerprint is scoped per cart")
	}
	c := a
	c.Parcels = []domain.Parcel{{WeightLb: 1}, {WeightLb: 3}}
	if a.Fingerprint("cart-1") == c.Fingerprint("cart-1") {
		t.Fatal("weight changes the fingerprint")
	}
}

func TestShipmentValidation(t *testing.T) {
	s := domain.Shipment{From: domain.Address{PostalCode: "77002", Country: "US"}, To: domain.Address{PostalCode: "SE1", Country: "GB"},
		Parcels: []domain.Parcel{{WeightLb: 2}}}
	if err := s.Validate(); !errors.Is(err, domain.ErrInvalid) {
		t.Fatal("international shipment without customs must be invalid")
	}
	s.Customs = &domain.Customs{Currency: "USD", Lines: []domain.CustomsLine{{Description: "Spice", Quantity: 2, UnitValueMinor: 500, HSCode: "0910.99"}}}
	if err := s.Validate(); err != nil || s.Customs.DeclaredValueMinor() != 1000 {
		t.Fatalf("valid customs: %v", err)
	}
	s.Parcels[0].WeightLb = 0
	if err := s.Validate(); !errors.Is(err, domain.ErrInvalid) {
		t.Fatal("zero weight must be invalid")
	}
}

func TestCanVoid(t *testing.T) {
	l := domain.LabelRecord{Status: domain.LabelPurchased, TrackingState: domain.StateLabelCreated}
	if err := l.CanVoid(); err != nil {
		t.Fatal(err)
	}
	l.TrackingState = domain.StatePickedUp
	if err := l.CanVoid(); !errors.Is(err, domain.ErrConflict) {
		t.Fatal("accepted parcel must not be voidable")
	}
	l = domain.LabelRecord{Status: domain.LabelVoided}
	if err := l.CanVoid(); !errors.Is(err, domain.ErrConflict) {
		t.Fatal("double void must conflict")
	}
}

func TestMoney(t *testing.T) {
	if m, _ := domain.ParseMinor("14.27"); m != 1427 {
		t.Fatalf("ParseMinor = %d", m)
	}
	if domain.FormatMajor(905) != "9.05" || domain.MinorFromFloat(10.485) != 1049 {
		t.Fatal("formatting/rounding")
	}
	q := domain.Quote{AmountMinor: 1000}.Adjust(-5)
	if q.AmountMinor != 950 {
		t.Fatalf("adjusted %d", q.AmountMinor)
	}
}

func TestPickupLocationValidation(t *testing.T) {
	l := domain.PickupLocation{Kind: domain.PickupSeller, OwnerOrg: "org_1", Name: "Mama Put Shop", Timezone: "America/Chicago",
		Address: domain.Address{Line1: "9 Bissonnet St", City: "Houston", State: "TX", PostalCode: "77005"},
		Hours:   []domain.OpeningHours{{Weekday: time.Monday, Open: "09:00", Close: "18:00"}}}
	if err := l.Validate(); err != nil {
		t.Fatal(err)
	}
	l.Hours[0].Close = "08:00"
	if err := l.Validate(); !errors.Is(err, domain.ErrInvalid) {
		t.Fatal("close before open must be invalid")
	}
}
