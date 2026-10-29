package domain_test

import (
	"reflect"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

func TestBackOfficesOf(t *testing.T) {
	seller := func(status string) domain.Membership { return domain.Membership{Kind: "SELLER", Status: status} }
	provider := func(status string) domain.Membership { return domain.Membership{Kind: "PROVIDER", Status: status} }
	bo := func(app, state string) domain.BackOffice { return domain.BackOffice{App: app, State: state} }
	for name, tc := range map[string]struct {
		pt    string
		roles []string
		orgs  []domain.Membership
		want  []domain.BackOffice
	}{
		"shopper only": {pt: "CUSTOMER", roles: []string{"account.customer"}, want: []domain.BackOffice{}},
		"approved seller (APP-30200 Malo Branding)": {pt: "SELLER_MEMBER", roles: []string{"account.customer", "seller.owner"},
			orgs: []domain.Membership{seller("APPROVED")}, want: []domain.BackOffice{bo("seller-center", "active")}},
		"seller application in review": {pt: "SELLER_MEMBER", roles: []string{"account.customer"},
			orgs: []domain.Membership{seller("PENDING")}, want: []domain.BackOffice{bo("seller-center", "application")}},
		"started selling, no organisation yet": {pt: "CUSTOMER", roles: []string{"account.customer", "account.seller_applicant"},
			want: []domain.BackOffice{bo("seller-center", "application")}},
		"approved and pending sellers": {pt: "SELLER_MEMBER", orgs: []domain.Membership{seller("PENDING"), seller("APPROVED")},
			want: []domain.BackOffice{bo("seller-center", "active")}},
		"suspended seller still opens Seller Center": {pt: "SELLER_MEMBER", orgs: []domain.Membership{seller("SUSPENDED")},
			want: []domain.BackOffice{bo("seller-center", "active")}},
		"seller and provider": {pt: "SELLER_MEMBER", orgs: []domain.Membership{seller("APPROVED"), provider("APPROVED")},
			want: []domain.BackOffice{bo("seller-center", "active"), bo("provider-hub", "active")}},
		"provider applicant": {pt: "CUSTOMER", roles: []string{"account.provider_applicant"},
			want: []domain.BackOffice{bo("provider-hub", "application")}},
		"both kinds in one organisation": {pt: "SELLER_MEMBER", orgs: []domain.Membership{{Kind: "BOTH", Status: "APPROVED"}},
			want: []domain.BackOffice{bo("seller-center", "active")}},
		"staff":       {pt: "STAFF", roles: []string{"platform.ops_manager"}, want: []domain.BackOffice{bo("staff-console", "active")}},
		"driver only": {pt: "DRIVER", roles: []string{"platform.driver"}, want: []domain.BackOffice{}},
	} {
		if got := domain.BackOfficesOf(tc.pt, tc.roles, tc.orgs); !reflect.DeepEqual(got, tc.want) {
			t.Errorf("%s: got %v, want %v", name, got, tc.want)
		}
	}
}
