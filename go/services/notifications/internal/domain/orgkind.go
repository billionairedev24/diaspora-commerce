package domain

import "strings"

// OrgKind is the kind of a seller organisation as seller-service and identity-service publish it.
type OrgKind string

// Organisation kinds. OrgKindBoth (S215) sells products and offers services; its back office is Seller Center,
// which shows the seller and provider navigation groups.
const (
	OrgKindSeller   OrgKind = "SELLER"
	OrgKindProvider OrgKind = "PROVIDER"
	OrgKindBoth     OrgKind = "BOTH"
)

// ParseOrgKind reads a kind case-insensitively; unknown or empty values are treated as SELLER.
func ParseOrgKind(s string) OrgKind {
	switch OrgKind(strings.ToUpper(strings.TrimSpace(s))) {
	case OrgKindProvider:
		return OrgKindProvider
	case OrgKindBoth:
		return OrgKindBoth
	default:
		return OrgKindSeller
	}
}

// UsesProviderHub reports whether the organisation's back office is Provider Hub (providers only).
func (k OrgKind) UsesProviderHub() bool {
	return k == OrgKindProvider
}

// ProgramName is the back-office name used in emails: "Provider Hub" for providers, "Seller Center" otherwise.
func (k OrgKind) ProgramName() string {
	if k.UsesProviderHub() {
		return "Provider Hub"
	}
	return "Seller Center"
}
