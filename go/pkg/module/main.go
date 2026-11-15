package module

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/telemetry"
)

// Main is the whole body of a service's cmd/<svc>/main.go: build the module, set the process-wide logger and tracer
// provider (afrotransact-go does the same once for all modules), serve until SIGINT or SIGTERM.
func Main(name string, ctor Constructor) {
	if err := run(name, ctor); err != nil {
		slog.Error(name+" exited", "error", err)
		os.Exit(1)
	}
}

func run(name string, ctor Constructor) error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	m, err := ctor(Deps{})
	if err != nil {
		return err
	}
	slog.SetDefault(m.Logger)
	shutdown, err := telemetry.Setup(ctx, name, m.Env, m.Telemetry.OTLPEndpoint, m.Telemetry.SampleRatio)
	if err != nil {
		return fmt.Errorf("tracing: %w", err)
	}
	defer func() { _ = shutdown(context.WithoutCancel(ctx)) }()
	return Serve(ctx, m)
}
