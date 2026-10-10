package config

import "strings"

// Shared, environment-wide settings. In Kubernetes the at-service Helm library chart injects them into every pod,
// derived from global.domain in infra/argocd/envs/<env>/platform-values.yaml (docs/engineering/environments.md).
// Services tag their config fields with these names (`env:"PUBLIC_WEB_URL"`) instead of inventing their own, keep
// localhost values in config/local.yaml and the environment's own hosts in config/<env>.yaml.
const (
	EnvPlatformEnv     = "PLATFORM_ENV"      // local | dev | uat | prod
	EnvPlatformDomain  = "PLATFORM_DOMAIN"   // public base domain: dev.<zone> | uat.<zone> | <zone> in prod
	EnvPublicWebURL    = "PUBLIC_WEB_URL"    // storefront
	EnvSellerCenterURL = "SELLER_CENTER_URL" // Seller Center & Provider Hub
	EnvStaffConsoleURL = "STAFF_CONSOLE_URL" // staff console
	EnvAPIBaseURL      = "API_BASE_URL"      // gateway-bff (public API)
	EnvAuthIssuerURL   = "AUTH_ISSUER_URL"   // identity OIDC issuer
	EnvAuthTokenURL    = "AUTH_TOKEN_URL"    //nolint:gosec // env var name, not a credential; defaults to TokenURL(AUTH_ISSUER_URL)
	EnvMediaPublicURL  = "MEDIA_PUBLIC_URL"  // public media CDN
)

// TokenURL is the identity service's OAuth 2 token endpoint for an issuer ("" when issuer is empty).
func TokenURL(issuer string) string {
	return JoinURL(issuer, "/oauth2/token")
}

// JoinURL joins base and path with exactly one slash; it returns "" when base is empty so a missing base stays
// detectable instead of turning into a relative URL.
func JoinURL(base, path string) string {
	base = strings.TrimRight(strings.TrimSpace(base), "/")
	if base == "" {
		return ""
	}
	if path == "" {
		return base
	}
	return base + "/" + strings.TrimLeft(path, "/")
}

// FirstNonEmpty returns the first value that is not blank, e.g. an explicit setting before a derived default.
func FirstNonEmpty(values ...string) string {
	for _, v := range values {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}
