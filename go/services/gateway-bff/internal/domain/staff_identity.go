package domain

// Identity & access (Developer tools, S291): OAuth clients, sessions and tokens, signing keys, token inspector and
// authentication settings of the identity service. The whole module needs identity's own permission.
const (
	// IdentityModule is the console module key.
	IdentityModule = "identity"
	// PermIdentityAdmin is identity's permission for /v1/admin/identity/** (Super Admin by default).
	PermIdentityAdmin = "identity:admin"
)

// OneTimeSecret is a value the console shows once, right after it was created: the BFF never stores or logs it and the
// upstream cannot return it again.
type OneTimeSecret struct {
	Label string `json:"label"`
	Value string `json:"value"`
	Note  string `json:"note"`
}
