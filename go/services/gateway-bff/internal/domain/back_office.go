package domain

import "strings"

// Back offices a person can open from the storefront's account menu (contract BackOffice).
const (
	BackOfficeSellerCenter = "seller-center"
	BackOfficeProviderHub  = "provider-hub"
	BackOfficeStaffConsole = "staff-console"

	// BackOfficeActive opens the back office; BackOfficeApplication continues a seller or provider application.
	BackOfficeActive      = "active"
	BackOfficeApplication = "application"
)

// BackOffice is one entry of MeUser.BackOffices.
type BackOffice struct {
	App   string `json:"app"`
	State string `json:"state"`
}

// Membership is what the account menu needs of one of the person's organisations (identity's /v1/me orgs).
type Membership struct {
	Kind   string // SELLER, PROVIDER or BOTH
	Status string // identity's mirror: PENDING, APPROVED, SUSPENDED, REJECTED
}

// BackOfficesOf mirrors identity's landing rules (DefaultLandingPolicy) for the account menu. Seller Center
// applies when the person belongs to a seller (or seller-and-provider) organisation and Provider Hub to a provider
// organisation. Each is active once an organisation of that kind was approved (a suspended one still opens its
// back office to see why), otherwise an application in progress, as is holding the applicant role (an application
// identity does not know of yet). The staff console applies to staff (pt STAFF or a platform role other than
// driver). Never nil.
func BackOfficesOf(principalType string, roles []string, orgs []Membership) []BackOffice {
	out := []BackOffice{}
	if s := orgState(orgs, roles, "SELLER", "account.seller_applicant"); s != "" {
		out = append(out, BackOffice{App: BackOfficeSellerCenter, State: s})
	}
	if s := orgState(orgs, roles, "PROVIDER", "account.provider_applicant"); s != "" {
		out = append(out, BackOffice{App: BackOfficeProviderHub, State: s})
	}
	if isStaff(principalType, roles) {
		out = append(out, BackOffice{App: BackOfficeStaffConsole, State: BackOfficeActive})
	}
	return out
}

// orgState is active, application or "" for the organisations of kind (BOTH counts as a seller: Seller Center shows
// its seller and provider navigation in one portal).
func orgState(orgs []Membership, roles []string, kind, applicantRole string) string {
	member := false
	for _, o := range orgs {
		k := strings.ToUpper(o.Kind)
		if k != kind && (kind != "SELLER" || k != "BOTH") {
			continue
		}
		member = true
		if st := strings.ToUpper(o.Status); st == "APPROVED" || st == "SUSPENDED" {
			return BackOfficeActive
		}
	}
	for _, r := range roles {
		if r == applicantRole {
			member = true
		}
	}
	if member {
		return BackOfficeApplication
	}
	return ""
}

func isStaff(principalType string, roles []string) bool {
	if strings.EqualFold(principalType, "STAFF") {
		return true
	}
	for _, r := range roles {
		if strings.HasPrefix(r, "platform.") && r != "platform.driver" {
			return true
		}
	}
	return false
}
