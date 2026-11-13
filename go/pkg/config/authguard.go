package config

import (
	"fmt"
	"strings"
)

// RequireAuthOutsideLocal is the fail-closed guard for a service's "auth off" switch (AUTH_ENABLED=false): turning
// authentication off is allowed only for APP_ENV local (and test). Any other environment (dev, uat, prod, unknown)
// must refuse to start, so one stray environment variable cannot publish an unauthenticated API (security audit F5).
func RequireAuthOutsideLocal(env string, authEnabled bool) error {
	if authEnabled {
		return nil
	}
	switch strings.ToLower(strings.TrimSpace(env)) {
	case EnvLocal, "test":
		return nil
	}
	return fmt.Errorf("refusing to start: auth.enabled=false (AUTH_ENABLED=false) is allowed only with APP_ENV=local, got %q; remove AUTH_ENABLED from this environment", env)
}
