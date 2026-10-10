package domain_test

import (
	"reflect"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/authz-sync/internal/domain"
)

func ptr(s string) *string { return &s }

func TestRoleTupleMapsOrganisationAndPlatformRoles(t *testing.T) {
	cases := []struct {
		name string
		in   domain.RoleChanged
		want domain.Tuple
		ok   bool
	}{
		{"seller owner", domain.RoleChanged{UserID: "u1", RoleKey: "seller.owner", OrgID: ptr("org_a")},
			domain.Tuple{User: "user:u1", Relation: "owner", Object: "organization:org_a"}, true},
		{"provider owner", domain.RoleChanged{UserID: "u1", RoleKey: "provider.owner", OrgID: ptr("org_b")},
			domain.Tuple{User: "user:u1", Relation: "owner", Object: "organization:org_b"}, true},
		{"catalog", domain.RoleChanged{UserID: "u2", RoleKey: "seller.catalog", OrgID: ptr("org_a")},
			domain.Tuple{User: "user:u2", Relation: "catalog", Object: "organization:org_a"}, true},
		{"practitioner", domain.RoleChanged{UserID: "u3", RoleKey: "provider.practitioner", OrgID: ptr("org_b")},
			domain.Tuple{User: "user:u3", Relation: "practitioner", Object: "organization:org_b"}, true},
		{"platform support", domain.RoleChanged{UserID: "s1", RoleKey: "platform.customer_support"},
			domain.Tuple{User: "user:s1", Relation: "support", Object: domain.Platform}, true},
		{"market-scoped support", domain.RoleChanged{UserID: "s2", RoleKey: "platform.customer_support", Market: "uk"},
			domain.Tuple{User: "user:s2", Relation: "support", Object: "market:GB"}, true},
		{"super admin is never market-scoped", domain.RoleChanged{UserID: "r", RoleKey: "platform.super_admin",
			Market: "US"}, domain.Tuple{User: "user:r", Relation: "super_admin", Object: domain.Platform}, true},
		{"retail buyer", domain.RoleChanged{UserID: "b", RoleKey: "platform.retail_buyer"},
			domain.Tuple{User: "user:b", Relation: "catalog", Object: "organization:org_retail"}, true},
		{"driver not mirrored", domain.RoleChanged{UserID: "d", RoleKey: "platform.driver"}, domain.Tuple{}, false},
		{"applicant not mirrored", domain.RoleChanged{UserID: "c", RoleKey: "account.seller_applicant"},
			domain.Tuple{}, false},
		{"unknown org role", domain.RoleChanged{UserID: "c", RoleKey: "seller.intern", OrgID: ptr("org_a")},
			domain.Tuple{}, false},
		{"empty orgId is a platform role", domain.RoleChanged{UserID: "s", RoleKey: "platform.finance",
			OrgID: ptr("")}, domain.Tuple{User: "user:s", Relation: "finance", Object: domain.Platform}, true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got, ok := domain.RoleTuple(c.in, "org_retail")
			if ok != c.ok || got != c.want {
				t.Fatalf("RoleTuple = %+v, %v; want %+v, %v", got, ok, c.want, c.ok)
			}
		})
	}
}

func TestRoleChangeGrantsAndRevokes(t *testing.T) {
	e := domain.RoleChanged{UserID: "u1", RoleKey: "seller.finance", OrgID: ptr("org_a")}
	want := domain.Tuple{User: "user:u1", Relation: "finance", Object: "organization:org_a"}
	if c := domain.RoleChange(e, true, ""); len(c.Writes) != 1 || c.Writes[0] != want || len(c.Deletes) != 0 {
		t.Fatalf("grant = %+v", c)
	}
	if c := domain.RoleChange(e, false, ""); len(c.Deletes) != 1 || c.Deletes[0] != want || len(c.Writes) != 0 {
		t.Fatalf("revoke = %+v", c)
	}
	if c := domain.RoleChange(domain.RoleChanged{UserID: "u1", RoleKey: "platform.driver"}, true, ""); !c.Empty() {
		t.Fatalf("driver = %+v", c)
	}
}

func TestOrgChangeConvergesMarketAndKind(t *testing.T) {
	existing := []domain.Tuple{{User: "market:US", Relation: "market", Object: "organization:org_a"}}
	c := domain.OrgChange(domain.SellerOrgEvent{OrgID: "org_a", Kind: "PROVIDER", Country: "uk"}, existing)

	wantWrites := []domain.Tuple{
		{User: domain.Platform, Relation: "platform", Object: "organization:org_a"},
		{User: domain.Platform, Relation: "platform", Object: "market:GB"},
		{User: "market:GB", Relation: "market", Object: "organization:org_a"},
		{User: "user:*", Relation: "offers_services", Object: "organization:org_a"},
		{User: "client:*", Relation: "offers_services", Object: "organization:org_a"},
	}
	wantDeletes := []domain.Tuple{
		{User: "market:US", Relation: "market", Object: "organization:org_a"},
		{User: "user:*", Relation: "sells_products", Object: "organization:org_a"},
		{User: "client:*", Relation: "sells_products", Object: "organization:org_a"},
	}
	if !reflect.DeepEqual(c.Writes, wantWrites) {
		t.Fatalf("writes = %+v", c.Writes)
	}
	if !reflect.DeepEqual(c.Deletes, wantDeletes) {
		t.Fatalf("deletes = %+v", c.Deletes)
	}
}

func TestOrgChangeKeepsTheSameMarketAndSetsBothFlags(t *testing.T) {
	existing := []domain.Tuple{{User: "market:GB", Relation: "market", Object: "organization:org_b"}}
	c := domain.OrgChange(domain.SellerOrgEvent{OrgID: "org_b", Kind: "BOTH", Country: "GB"}, existing)
	if len(c.Deletes) != 0 {
		t.Fatalf("deletes = %+v", c.Deletes)
	}
	if len(c.Writes) != 7 {
		t.Fatalf("writes = %+v", c.Writes)
	}
}

func TestMarketCode(t *testing.T) {
	for _, c := range []struct{ in, want string }{{"us", "US"}, {" GB ", "GB"}, {"UK", "GB"}, {"USA", "US"}} {
		if got, ok := domain.MarketCode(c.in); !ok || got != c.want {
			t.Fatalf("MarketCode(%q) = %q, %v", c.in, got, ok)
		}
	}
	for _, bad := range []string{"", "Nigeria", "U1"} {
		if _, ok := domain.MarketCode(bad); ok {
			t.Fatalf("MarketCode(%q) accepted", bad)
		}
	}
}
