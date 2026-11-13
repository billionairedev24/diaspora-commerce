package app

import (
	"net/http"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// The catalog's department refusal is written for the seller; the BFF passes its code and message through instead of
// the generic "You can't do that".
func TestMapErrKeepsTheDepartmentRefusalMessage(t *testing.T) {
	const msg = "Your store is not set up to sell in Fashion. Choose “Need another department? Request it”."
	err := mapErr("catalog", &port.UpstreamProblem{Upstream: "catalog", Status: http.StatusForbidden,
		Code: "catalog.department-not-allowed", Detail: msg})
	p, ok := domain.AsProblem(err)
	if !ok || p.Status != http.StatusForbidden || p.Code != "catalog.department-not-allowed" || p.Detail != msg {
		t.Fatalf("problem = %+v", p)
	}
	other, _ := domain.AsProblem(mapErr("catalog", &port.UpstreamProblem{Upstream: "catalog", Status: http.StatusForbidden,
		Code: "auth.missing-permission", Detail: "internal detail"}))
	if other.Code != domain.CodeForbidden || other.Detail != "You can't do that." {
		t.Fatalf("other 403 = %+v", other)
	}
}

// A service that wants a recent sign-in (saved payment methods) keeps its code so the client can start a fresh sign-in.
func TestMapErrKeepsTheStepUpRequiredCode(t *testing.T) {
	err := mapErr("payments", &port.UpstreamProblem{Upstream: "payments", Status: http.StatusForbidden,
		Code: "auth.step-up-required", Detail: "Confirm it is you: sign in again to remove a payment method"})
	p, ok := domain.AsProblem(err)
	if !ok || p.Status != http.StatusForbidden || p.Code != "auth.step-up-required" ||
		p.Detail != "Confirm it is you: sign in again to remove a payment method" {
		t.Fatalf("problem = %+v", p)
	}
}
