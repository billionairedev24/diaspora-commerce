// Package logging builds the structured slog logger every service uses (JSON outside local).
package logging

import (
	"io"
	"log/slog"
	"os"
)

// New returns a logger tagged with service and environment. Local uses text output for humans.
func New(service, env string) *slog.Logger {
	return NewWithWriter(os.Stdout, service, env)
}

// NewWithWriter is New with an explicit writer.
func NewWithWriter(w io.Writer, service, env string) *slog.Logger {
	opts := &slog.HandlerOptions{Level: slog.LevelInfo}
	var h slog.Handler
	if env == "local" {
		opts.Level = slog.LevelDebug
		h = slog.NewTextHandler(w, opts)
	} else {
		h = slog.NewJSONHandler(w, opts)
	}
	return slog.New(h).With("service", service, "env", env)
}
