package app

import (
	"slices"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

func moderator() staffCtx {
	return staffCtx{access: domain.StaffAccess{
		Roles: []string{"platform.trust_safety"}, Permissions: []string{"catalog:moderate"}, Matrix: domain.DefaultPlatformMatrix,
	}}
}

func productMenu(sc staffCtx, o jobj) []string {
	keys := []string{}
	for _, a := range productsResource().rowMenu(sc, o) {
		keys = append(keys, a.Key)
	}
	return keys
}

// The catalog's state machine is the source of truth: the row menu offers exactly what the product says it allows.
func TestProductRowMenuFollowsTheCatalogsAllowedActions(t *testing.T) {
	sc := moderator()
	live := jobj{"id": "p1", "status": "LIVE", "allowedActions": []any{"pause", "takedown"}}
	if got := productMenu(sc, live); !slices.Equal(got, []string{"takedown"}) {
		t.Errorf("a live product must not offer Approve; got %v", got)
	}
	review := jobj{"id": "p2", "status": "IN_REVIEW", "allowedActions": []any{"approve", "reject"}}
	if got := productMenu(sc, review); !slices.Equal(got, []string{"approve", "reject"}) {
		t.Errorf("in review: %v", got)
	}
	taken := jobj{"id": "p3", "status": "TAKEN_DOWN", "allowedActions": []any{"restore"}}
	if got := productMenu(sc, taken); !slices.Equal(got, []string{"restore"}) {
		t.Errorf("taken down: %v", got)
	}
	if got := productMenu(sc, jobj{"id": "p4", "status": "REJECTED", "allowedActions": []any{}}); len(got) != 0 {
		t.Errorf("rejected offers nothing, got %v", got)
	}
}

func TestProductActionsFallBackToStatusForACatalogWithoutAllowedActions(t *testing.T) {
	sc := moderator()
	if got := productMenu(sc, jobj{"id": "p1", "status": "LIVE"}); slices.Contains(got, "approve") {
		t.Errorf("a live product must not offer Approve even without allowedActions: %v", got)
	}
	if got := productMenu(sc, jobj{"id": "p2", "status": "IN_REVIEW"}); !slices.Contains(got, "approve") {
		t.Errorf("in review offers Approve: %v", got)
	}
}

func TestProductActionsNeedTheModeratePermission(t *testing.T) {
	sc := moderator()
	sc.access.Permissions = nil
	if got := productMenu(sc, jobj{"id": "p1", "status": "IN_REVIEW", "allowedActions": []any{"approve", "reject"}}); len(got) != 0 {
		t.Errorf("no permission, no actions: %v", got)
	}
}

func TestAListingClosedByTheTaxGateReadsHeldForTax(t *testing.T) {
	held := jobj{"id": "p1", "status": "IN_REVIEW", "heldForTax": true, "allowedActions": []any{"approve", "reject"}}
	if got := productStatusOf(held); got != "HELD_FOR_TAX" {
		t.Fatalf("status = %q", got)
	}
	if got := productStatusOf(jobj{"status": "IN_REVIEW", "heldForTax": false}); got != "IN_REVIEW" {
		t.Fatalf("an ordinary review reads %q", got)
	}
	r := productsResource()
	if cells := r.cells(held); cells["status"] != "HELD_FOR_TAX" {
		t.Fatalf("cells = %v", cells)
	}
	if domain.Humanize("HELD_FOR_TAX") != "Held for tax" {
		t.Fatalf("label = %q", domain.Humanize("HELD_FOR_TAX"))
	}
	var facet *domain.Facet
	for i := range r.facets {
		if r.facets[i].Value == "HELD_FOR_TAX" {
			facet = &r.facets[i]
		}
	}
	if facet == nil || facet.Label != "Held for tax" {
		t.Fatalf("the status filter must include Held for tax: %v", r.facets)
	}
	if tone := domain.StatusTone("HELD_FOR_TAX"); tone == nil || *tone != "warn" {
		t.Fatalf("tone = %v", tone)
	}
}
