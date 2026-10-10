package telemetry_test

import (
	"context"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/telemetry"
)

// Regression: the semconv schema must match the SDK's default resource or Setup fails when an endpoint is set.
func TestSetupWithEndpoint(t *testing.T) {
	shutdown, err := telemetry.Setup(context.Background(), "test", "dev", "localhost:4318", 1.0)
	if err != nil {
		t.Fatalf("setup: %v", err)
	}
	_ = shutdown(context.Background())
}

func TestSetupWithoutEndpointIsNoop(t *testing.T) {
	shutdown, err := telemetry.Setup(context.Background(), "test", "local", "", 1.0)
	if err != nil {
		t.Fatal(err)
	}
	if err := shutdown(context.Background()); err != nil {
		t.Fatal(err)
	}
}
