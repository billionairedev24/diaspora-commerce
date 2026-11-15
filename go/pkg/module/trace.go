package module

import (
	"context"

	"go.opentelemetry.io/otel/attribute"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
)

type ctxKey struct{}

// WithName returns a context that marks the work as running inside the named module. afrotransact-go sets it on every
// request it routes and on every in-process call, so the span processor can tag spans with the module.
func WithName(ctx context.Context, name string) context.Context {
	return context.WithValue(ctx, ctxKey{}, name)
}

// NameFrom returns the module name set by WithName, or "".
func NameFrom(ctx context.Context) string {
	n, _ := ctx.Value(ctxKey{}).(string)
	return n
}

type spanProcessor struct{}

var _ sdktrace.SpanProcessor = spanProcessor{}

func (spanProcessor) OnStart(ctx context.Context, s sdktrace.ReadWriteSpan) {
	if n := NameFrom(ctx); n != "" {
		s.SetAttributes(attribute.String("module", n))
	}
}
func (spanProcessor) OnEnd(sdktrace.ReadOnlySpan)      {}
func (spanProcessor) Shutdown(context.Context) error   { return nil }
func (spanProcessor) ForceFlush(context.Context) error { return nil }

// SpanProcessor adds the module attribute to every span started under a context marked with WithName.
func SpanProcessor() sdktrace.SpanProcessor { return spanProcessor{} }
