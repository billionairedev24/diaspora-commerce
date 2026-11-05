package app

import (
	"context"
	"slices"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type recordingUp struct{ calls []port.StaffCall }

func (r *recordingUp) Name() string { return "trust-ops" }

func (r *recordingUp) Call(_ context.Context, _ string, c port.StaffCall, _ any) error {
	r.calls = append(r.calls, c)
	return nil
}

func enforcer(perms ...string) staffCtx {
	return staffCtx{access: domain.StaffAccess{
		Roles: []string{"platform.trust_safety"}, Permissions: perms, Matrix: domain.DefaultPlatformMatrix,
	}}
}

func menuKeys(r *staffResource, sc staffCtx, status string) []string {
	keys := []string{}
	for _, a := range r.rowMenu(sc, jobj{"orgId": "org_1", "status": status}) {
		keys = append(keys, a.Key)
	}
	return keys
}

func TestOrganisationRowMenuSuspendsApprovedAndReinstatesSuspended(t *testing.T) {
	r := organisationsResource()
	sc := enforcer("sellers:enforce")
	if got := menuKeys(r, sc, "APPROVED"); !slices.Equal(got, []string{"suspend"}) {
		t.Errorf("approved: %v", got)
	}
	if got := menuKeys(r, sc, "SUSPENDED"); !slices.Equal(got, []string{"reinstate"}) {
		t.Errorf("suspended: %v", got)
	}
	for _, status := range []string{"PENDING_REVIEW", "DRAFT", "REJECTED"} {
		if got := menuKeys(r, sc, status); len(got) != 0 {
			t.Errorf("%s: %v", status, got)
		}
	}
	// Without the permission the menu is empty (Trust & Safety only has VIEW on the module: the permission is the gate).
	if got := menuKeys(r, enforcer("orgs:read"), "APPROVED"); len(got) != 0 {
		t.Errorf("no sellers:enforce: %v", got)
	}
	// The same actions are on the sheet, greyed out with a reason when the state or permission does not fit.
	o := jobj{"orgId": "org_1", "status": "SUSPENDED"}
	for _, a := range r.actions {
		reason, perm := actionReason(r, sc, a, o)
		switch a.key {
		case "suspend":
			if reason == "" || perm {
				t.Errorf("suspend on a suspended organisation: %q", reason)
			}
		case "reinstate":
			if reason != "" {
				t.Errorf("reinstate: %q", reason)
			}
		}
	}
}

func TestSuspendAndReinstateNeedAReasonOfTenCharactersAndCallTrustOps(t *testing.T) {
	r := organisationsResource()
	suspend := suspendOrgForm(context.Background(), staffCtx{}, nil)
	if errs := domain.ValidateForm(*suspend, map[string]any{"reason": "FRAUD_RISK", "note": "updates"}); !strings.Contains(errs["note"], "at least 10 characters") {
		t.Errorf("short note: %v", errs)
	}
	if errs := domain.ValidateForm(*suspend, map[string]any{"reason": "FRAUD_RISK", "note": "   fraud  "}); errs["note"] == "" {
		t.Errorf("spaces do not count: %v", errs)
	}
	if errs := domain.ValidateForm(*suspend, map[string]any{"reason": "NOPE", "note": "Chargebacks on three orders"}); errs["reason"] == "" {
		t.Errorf("unknown reason: %v", errs)
	}
	if errs := domain.ValidateForm(*suspend, map[string]any{"reason": "FRAUD_RISK", "note": "Chargebacks on three orders"}); len(errs) != 0 {
		t.Errorf("valid: %v", errs)
	}

	up := &recordingUp{}
	sc := enforcer("sellers:enforce")
	sc.up = port.StaffUpstreams{TrustOps: up}
	o := jobj{"orgId": "org/1", "status": "APPROVED"}
	for _, a := range r.actions {
		var v jobj
		if a.key == "suspend" {
			v = jobj{"reason": "FRAUD_RISK", "note": " Chargebacks on three orders "}
		} else {
			v = jobj{"note": "Cleared after review"}
		}
		if err := a.run(context.Background(), sc, o, v); err != nil {
			t.Fatal(err)
		}
	}
	if len(up.calls) != 2 || up.calls[0].Path != "/v1/seller-enforcement/org%2F1/suspend" || up.calls[1].Path != "/v1/seller-enforcement/org%2F1/reinstate" {
		t.Fatalf("calls: %+v", up.calls)
	}
	if b, _ := up.calls[0].Body.(jobj); b["reason"] != "FRAUD_RISK" || b["note"] != "Chargebacks on three orders" {
		t.Errorf("suspend body: %+v", up.calls[0].Body)
	}
	if tone := domain.StatusTone("SUSPENDED"); tone == nil || *tone != "bad" {
		t.Errorf("suspended tone: %v", tone)
	}
}
