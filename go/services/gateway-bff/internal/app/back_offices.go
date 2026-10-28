package app

import (
	"context"
	"log/slog"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ApplicationReader reads the caller's own seller or provider application at the seller service (GET
// /v1/applications/me; port.SellerClient implements it).
type ApplicationReader interface {
	Application(ctx context.Context, bearer string) (port.SellerApplication, error)
}

// orgHealer is what repairing identity's copy of an organisation needs (see healOrg); zero value: no repair.
type orgHealer struct {
	orgs     OrgSyncer
	sessions TokenRefresher
	log      *slog.Logger
}

// applicationCheckTimeout bounds the seller-service lookup /v1/me makes for an application in progress (best effort).
const applicationCheckTimeout = 1500 * time.Millisecond

// backOffices are the account menu's back offices (Seller Center, Provider Hub, staff console) of identity's /v1/me.
func backOffices(me port.IdentityMe) []domain.BackOffice {
	orgs := make([]domain.Membership, 0, len(me.Orgs))
	for _, o := range me.Orgs {
		orgs = append(orgs, domain.Membership{Kind: o.Kind, Status: o.Status})
	}
	return domain.BackOfficesOf(me.PrincipalType, me.Roles, orgs)
}

// confirmApproved turns a back office identity still shows as an application into the back office itself when the
// seller service has approved that application: identity mirrors the approval asynchronously (and the person keeps
// the applicant role), so for a while an approved seller would otherwise be sent to onboarding from the account menu
// ("Continue your seller application") instead of their Seller Center. Identity is then asked to repair its copy
// (healOrg) so the next tokens carry the organisation. Best effort: without an answer nothing changes.
func confirmApproved(ctx context.Context, apps ApplicationReader, h orgHealer, c *domain.Caller, offices []domain.BackOffice) []domain.BackOffice {
	if apps == nil || c == nil || c.BearerToken == "" {
		return offices
	}
	bearer := c.BearerToken
	pending := false
	for _, o := range offices {
		pending = pending || (o.State == domain.BackOfficeApplication && (o.App == domain.BackOfficeSellerCenter || o.App == domain.BackOfficeProviderHub))
	}
	if !pending {
		return offices
	}
	// The person's own application, with the token as issued (not acting as a shopper on this storefront route).
	actx, cancel := context.WithTimeout(port.WithoutShopper(ctx), applicationCheckTimeout)
	defer cancel()
	a, err := apps.Application(actx, bearer)
	if err != nil || !strings.EqualFold(a.Status, "APPROVED") {
		return offices
	}
	kind := strings.ToUpper(a.Kind)
	// Not acting as a shopper: identity checks the person is the organisation's owner or approved applicant.
	healOrg(port.WithoutShopper(ctx), h.orgs, h.sessions, h.log, c, a.OrgID)
	out := make([]domain.BackOffice, len(offices))
	for i, o := range offices {
		seller := o.App == domain.BackOfficeSellerCenter && (kind == "SELLER" || kind == "BOTH" || kind == "")
		provider := o.App == domain.BackOfficeProviderHub && kind == "PROVIDER"
		if o.State == domain.BackOfficeApplication && (seller || provider) {
			o.State = domain.BackOfficeActive
		}
		out[i] = o
	}
	return out
}
