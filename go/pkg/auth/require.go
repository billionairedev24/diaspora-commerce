package auth

import "fmt"

// RequireVerifier is the startup guard of every service: with a nil verifier Middleware trusts the claims of an
// unsigned token (LocalActor), which is only acceptable on a developer machine. Outside env "local" a nil verifier
// (auth.enabled switched off by config or environment) must stop the service instead of opening every tenant's data.
func RequireVerifier(env string, v Verifier) error {
	if v == nil && env != "local" {
		return fmt.Errorf("authentication is disabled in the %q environment: only the local profile may run without a token verifier", env)
	}
	return nil
}
