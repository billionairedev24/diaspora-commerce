package app

import (
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// backOffices are the account menu's back offices (Seller Center, Provider Hub, staff console) of identity's /v1/me.
func backOffices(me port.IdentityMe) []domain.BackOffice {
	orgs := make([]domain.Membership, 0, len(me.Orgs))
	for _, o := range me.Orgs {
		orgs = append(orgs, domain.Membership{Kind: o.Kind, Status: o.Status})
	}
	return domain.BackOfficesOf(me.PrincipalType, me.Roles, orgs)
}
