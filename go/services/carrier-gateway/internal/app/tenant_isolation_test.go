package app_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
)

// platformActor is a service caller (fulfilment, tax-returns): it reads every label.
var platformActor = auth.Actor{Subject: "svc-fulfilment", Type: "SERVICE", Permissions: []string{app.PermLabel, app.PermVoid}}

func orgMember(org string) auth.Actor {
	return auth.Actor{Subject: "user_" + org, Type: "SELLER_MEMBER", OrgID: org,
		Permissions: []string{app.PermRate, app.PermLabel, app.PermVoid}}
}

// TestLabelsAreReadableAndVoidableOnlyByTheirOrganisation: a seller holds shipping:label and shipping:void for its
// own labels; another organisation's token must neither read the label, nor print its document, nor void it.
func TestLabelsAreReadableAndVoidableOnlyByTheirOrganisation(t *testing.T) {
	ups := &fakeCarrier{code: domain.UPS}
	store := newMemStore()
	svc := newLabels(ups, store)
	ctx := context.Background()
	owner, intruder := orgMember("org_a"), orgMember("org_b")
	staff := auth.Actor{Subject: "ops", Type: "STAFF", Permissions: []string{app.PermLabel, app.PermVoid}}

	label, err := svc.Buy(ctx, app.BuyRequest{IdempotencyKey: "k", Caller: "org_a", Shipment: shipment("A-1"), Carrier: domain.UPS, Service: "03",
		Payer: domain.LabelPayer{OrgID: "org_a"}})
	if err != nil {
		t.Fatal(err)
	}

	if _, err := svc.Get(ctx, intruder, label.ID); !errors.Is(err, domain.ErrNotFound) {
		t.Fatalf("get another org's label: %v", err)
	}
	if _, _, err := svc.Document(ctx, intruder, label.ID); !errors.Is(err, domain.ErrNotFound) {
		t.Fatalf("print another org's label: %v", err)
	}
	if _, _, err := svc.DocumentByTracking(ctx, intruder, domain.UPS, label.TrackingNumber); !errors.Is(err, domain.ErrNotFound) {
		t.Fatalf("print another org's label by tracking number: %v", err)
	}
	if _, err := svc.Void(ctx, intruder, label.ID); !errors.Is(err, domain.ErrNotFound) || ups.voidCalls.Load() != 0 {
		t.Fatalf("void another org's label: %v (carrier void calls %d)", err, ups.voidCalls.Load())
	}
	customer := auth.Actor{Subject: "cust", Type: "CUSTOMER", Permissions: []string{app.PermLabel}}
	if _, err := svc.Get(ctx, customer, label.ID); !errors.Is(err, domain.ErrNotFound) {
		t.Fatalf("a shopper has no organisation and reads no label: %v", err)
	}

	if got, err := svc.Get(ctx, owner, label.ID); err != nil || got.ID != label.ID {
		t.Fatalf("owner reads own label: %v", err)
	}
	if _, _, err := svc.Document(ctx, owner, label.ID); err != nil {
		t.Fatalf("owner prints own label: %v", err)
	}
	if _, _, err := svc.DocumentByTracking(ctx, staff, domain.UPS, label.TrackingNumber); err != nil {
		t.Fatalf("staff prints any label: %v", err)
	}
	if _, err := svc.Void(ctx, owner, label.ID); err != nil || ups.voidCalls.Load() != 1 {
		t.Fatalf("owner voids own label: %v", err)
	}
}

// TestPlatformLabelsBelongToNoOrganisation: a label bought by a platform caller has no organisation, so no seller reads it.
func TestPlatformLabelsBelongToNoOrganisation(t *testing.T) {
	svc := newLabels(&fakeCarrier{code: domain.UPS}, newMemStore())
	ctx := context.Background()
	label, err := svc.Buy(ctx, app.BuyRequest{IdempotencyKey: "k", Caller: "svc", Shipment: shipment("P-1"), Carrier: domain.UPS, Service: "03",
		Payer: domain.LabelPayer{Platform: true}})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := svc.Get(ctx, orgMember("org_a"), label.ID); !errors.Is(err, domain.ErrNotFound) {
		t.Fatalf("seller reads a platform label: %v", err)
	}
	if _, err := svc.Get(ctx, auth.Actor{Type: "SERVICE", Subject: "fulfilment"}, label.ID); err != nil {
		t.Fatalf("service reads platform label: %v", err)
	}
}

// TestTrackingOfAnotherOrganisationsLabelIsRefused: tracking numbers of purchased labels are scoped to the buyer.
func TestTrackingOfAnotherOrganisationsLabelIsRefused(t *testing.T) {
	ups := &fakeCarrier{code: domain.UPS}
	store := newMemStore()
	labels := newLabels(ups, store)
	ctx := context.Background()
	label, err := labels.Buy(ctx, app.BuyRequest{IdempotencyKey: "k", Caller: "org_a", Shipment: shipment("A-1"), Carrier: domain.UPS, Service: "03",
		Payer: domain.LabelPayer{OrgID: "org_a"}})
	if err != nil {
		t.Fatal(err)
	}
	if err := labels.CheckTrackingAccess(ctx, orgMember("org_b"), domain.UPS, label.TrackingNumber); !errors.Is(err, domain.ErrNotFound) {
		t.Fatalf("another org tracks the label: %v", err)
	}
	if err := labels.CheckTrackingAccess(ctx, orgMember("org_a"), domain.UPS, label.TrackingNumber); err != nil {
		t.Fatalf("owner tracks: %v", err)
	}
	if err := labels.CheckTrackingAccess(ctx, orgMember("org_b"), domain.UPS, "1Z-NOT-OURS"); err != nil {
		t.Fatalf("a number we sold no label for is carrier-public information: %v", err)
	}
}

// TestInactivePickupLocationIsHiddenFromOthers: a seller's location is a draft until switched on; anonymous callers and
// other organisations must not read its address, and the audit fields of an active one are not public.
func TestInactivePickupLocationIsHiddenFromOthers(t *testing.T) {
	hours := []domain.OpeningHours{{Weekday: time.Monday, Open: "09:00", Close: "17:00"}}
	addr := domain.Address{Line1: "1 Main St", City: "Houston", State: "TX", PostalCode: "77002"}
	enabled := time.Now()
	repo := &memPickups{m: map[string]domain.PickupLocation{
		"draft": {ID: "draft", Kind: domain.PickupSeller, OwnerOrg: "org_a", Name: "Draft", Address: addr, Timezone: "America/Chicago", Hours: hours},
		"live": {ID: "live", Kind: domain.PickupSeller, OwnerOrg: "org_a", Name: "Live", Address: addr, Timezone: "America/Chicago", Hours: hours,
			Active: true, EnabledBy: "user_org_a", EnabledAt: &enabled},
	}}
	svc := app.NewPickupService(app.Carriers{}, allAccounts(), repo, newMemStore(), time.Now)
	ctx := context.Background()

	for name, actor := range map[string]auth.Actor{"anonymous": {}, "other org": orgMember("org_b"), "shopper": {Type: "CUSTOMER", Subject: "c"}} {
		if _, err := svc.GetLocation(ctx, actor, "draft"); !errors.Is(err, domain.ErrNotFound) {
			t.Fatalf("%s reads a draft location: %v", name, err)
		}
		live, err := svc.GetLocation(ctx, actor, "live")
		if err != nil || live.EnabledBy != "" || live.EnabledAt != nil {
			t.Fatalf("%s: live location must be public without audit fields: %+v %v", name, live, err)
		}
		list, err := svc.ListLocations(ctx, actor, "", "")
		if err != nil || len(list) != 1 || list[0].EnabledBy != "" {
			t.Fatalf("%s: public list leaks audit fields: %+v %v", name, list, err)
		}
	}
	if d, err := svc.GetLocation(ctx, orgMember("org_a"), "draft"); err != nil || d.ID != "draft" {
		t.Fatalf("owner reads own draft: %v", err)
	}
	if l, err := svc.GetLocation(ctx, orgMember("org_a"), "live"); err != nil || l.EnabledBy != "user_org_a" {
		t.Fatalf("owner sees its audit fields: %+v %v", l, err)
	}
}
