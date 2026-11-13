package auth_test

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
)

func TestRequireVerifierRefusesUnauthenticatedModeOutsideLocal(t *testing.T) {
	verifier := auth.NewJWKSVerifier("https://issuer.example", "svc", nil)
	for _, env := range []string{"dev", "uat", "prod", ""} {
		if err := auth.RequireVerifier(env, nil); err == nil {
			t.Fatalf("env %q started without a verifier", env)
		}
		if err := auth.RequireVerifier(env, verifier); err != nil {
			t.Fatalf("env %q with a verifier: %v", env, err)
		}
	}
	if err := auth.RequireVerifier("local", nil); err != nil {
		t.Fatalf("local may run without authentication: %v", err)
	}
}
