// Package domain maps identity and seller-org events to OpenFGA relationship tuples (contracts/openfga/model.fga).
package domain

import (
	"regexp"
	"strings"
)

// Event types consumed.
const (
	RoleGranted       = "identity.role.granted"
	RoleRevoked       = "identity.role.revoked"
	SellerOrgApproved = "seller.org.approved"
	SellerOrgUpdated  = "seller.org.updated"
)

// Platform is the single platform object holding platform-wide staff roles.
const Platform = "platform:afrotransact"

// Tuple is one relationship: User has Relation on Object.
type Tuple struct {
	User     string `json:"user"`
	Relation string `json:"relation"`
	Object   string `json:"object"`
}

// Change is the tuples to write and delete for one event, applied atomically and idempotently.
type Change struct {
	Writes  []Tuple
	Deletes []Tuple
}

// Empty reports whether the change does nothing.
func (c Change) Empty() bool { return len(c.Writes) == 0 && len(c.Deletes) == 0 }

// RoleChanged mirrors identity's IdentityRoleChanged payload (identity.role.granted / revoked).
type RoleChanged struct {
	UserID  string  `json:"userId"`
	RoleKey string  `json:"roleKey"`
	OrgID   *string `json:"orgId"`
	// Market scopes a platform role to one market (ISO 3166-1 alpha-2). Not published by identity yet; when absent
	// the grant is platform-wide.
	Market string `json:"market"`
}

// SellerOrgEvent mirrors seller-service's SellerOrgEvent (seller.org.approved / updated), the fields used here.
type SellerOrgEvent struct {
	OrgID   string `json:"orgId"`
	Kind    string `json:"kind"`
	Country string `json:"country"`
}

// orgRoles maps identity organisation role keys to organisation relations.
var orgRoles = map[string]string{
	"seller.owner":          "owner",
	"provider.owner":        "owner",
	"seller.admin":          "admin",
	"provider.manager":      "manager",
	"seller.catalog":        "catalog",
	"seller.fulfilment":     "fulfilment",
	"seller.finance":        "finance",
	"provider.finance":      "finance",
	"seller.ads":            "ads",
	"seller.support":        "support",
	"provider.practitioner": "practitioner",
}

// platformRoles maps identity platform (staff) role keys to platform / market relations. Roles without a staff
// scope (drivers, developers, product owners) are not mirrored.
var platformRoles = map[string]string{
	"platform.super_admin":        "super_admin",
	"platform.customer_support":   "support",
	"platform.catalog_manager":    "catalog_manager",
	"platform.finance":            "finance",
	"platform.trust_safety":       "trust_safety",
	"platform.operations_manager": "operations",
	"platform.fulfilment_ops":     "operations",
	"platform.dispatcher":         "operations",
	"platform.inventory_manager":  "operations",
	"platform.marketing":          "marketing",
	"platform.auditor":            "auditor",
}

// retailBuyer acts for AfroTransact Retail's organisation with the catalog role.
const retailBuyer = "platform.retail_buyer"

var marketCode = regexp.MustCompile(`^[A-Z]{2}$`)

// RoleTuple maps a role event to its tuple; ok is false for roles the model does not mirror.
func RoleTuple(e RoleChanged, retailOrgID string) (Tuple, bool) {
	if e.UserID == "" {
		return Tuple{}, false
	}
	user := "user:" + e.UserID
	if e.OrgID != nil && *e.OrgID != "" {
		rel, ok := orgRoles[e.RoleKey]
		if !ok {
			return Tuple{}, false
		}
		return Tuple{User: user, Relation: rel, Object: "organization:" + *e.OrgID}, true
	}
	if e.RoleKey == retailBuyer && retailOrgID != "" {
		return Tuple{User: user, Relation: "catalog", Object: "organization:" + retailOrgID}, true
	}
	rel, ok := platformRoles[e.RoleKey]
	if !ok {
		return Tuple{}, false
	}
	if market, valid := MarketCode(e.Market); valid && rel != "super_admin" {
		return Tuple{User: user, Relation: rel, Object: "market:" + market}, true
	}
	return Tuple{User: user, Relation: rel, Object: Platform}, true
}

// RoleChange is the change for a granted (true) or revoked (false) role.
func RoleChange(e RoleChanged, granted bool, retailOrgID string) Change {
	t, ok := RoleTuple(e, retailOrgID)
	switch {
	case !ok:
		return Change{}
	case granted:
		return Change{Writes: []Tuple{t}}
	default:
		return Change{Deletes: []Tuple{t}}
	}
}

// MarketCode normalises a country to content's market code (ISO 3166-1 alpha-2, UK → GB), as identity does.
func MarketCode(country string) (string, bool) {
	c := strings.ToUpper(strings.TrimSpace(country))
	switch c {
	case "UK", "GBR":
		c = "GB"
	case "USA":
		c = "US"
	}
	return c, marketCode.MatchString(c)
}

// OrgChange is the desired state of an organisation's platform link, market and kind flags. existingMarkets are the
// organisation's current market tuples (from the store); markets other than the event's are deleted.
func OrgChange(e SellerOrgEvent, existingMarkets []Tuple) Change {
	if e.OrgID == "" {
		return Change{}
	}
	org := "organization:" + e.OrgID
	c := Change{Writes: []Tuple{
		{User: Platform, Relation: "platform", Object: org},
	}}
	market, hasMarket := MarketCode(e.Country)
	if hasMarket {
		mk := "market:" + market
		c.Writes = append(c.Writes, Tuple{User: Platform, Relation: "platform", Object: mk},
			Tuple{User: mk, Relation: "market", Object: org})
	}
	for _, t := range existingMarkets {
		if !hasMarket || t.User != "market:"+market {
			c.Deletes = append(c.Deletes, Tuple{User: t.User, Relation: "market", Object: org})
		}
	}
	products, services := kindFlags(e.Kind)
	for _, flag := range []struct {
		relation string
		on       bool
	}{{"sells_products", products}, {"offers_services", services}} {
		for _, wildcard := range []string{"user:*", "client:*"} {
			t := Tuple{User: wildcard, Relation: flag.relation, Object: org}
			if flag.on {
				c.Writes = append(c.Writes, t)
			} else {
				c.Deletes = append(c.Deletes, t)
			}
		}
	}
	return c
}

func kindFlags(kind string) (products, services bool) {
	switch strings.ToUpper(kind) {
	case "SELLER":
		return true, false
	case "PROVIDER":
		return false, true
	case "BOTH":
		return true, true
	default:
		return false, false
	}
}
