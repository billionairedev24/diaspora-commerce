// Package telemetry configures OpenTelemetry tracing (OTLP/HTTP to the OTel Collector).
package telemetry

import (
	"context"
	"fmt"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.43.0"
)

// Shutdown flushes and stops the tracer provider.
type Shutdown func(context.Context) error

// Setup installs a global tracer provider. When endpoint is empty, tracing is a no-op.
// endpoint is host:port of the collector's OTLP/HTTP receiver, sampleRatio in [0,1].
func Setup(ctx context.Context, service, env, endpoint string, sampleRatio float64) (Shutdown, error) {
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(propagation.TraceContext{}, propagation.Baggage{}))
	if endpoint == "" {
		return func(context.Context) error { return nil }, nil
	}
	exp, err := otlptracehttp.New(ctx, otlptracehttp.WithEndpoint(endpoint), otlptracehttp.WithInsecure())
	if err != nil {
		return nil, fmt.Errorf("otlp exporter: %w", err)
	}
	res, err := resource.Merge(resource.Default(), resource.NewWithAttributes(semconv.SchemaURL,
		semconv.ServiceName(service), semconv.ServiceNamespace("afrotransact"),
		semconv.DeploymentEnvironmentNameKey.String(env)))
	if err != nil {
		return nil, fmt.Errorf("otel resource: %w", err)
	}
	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exp),
		sdktrace.WithResource(res),
		sdktrace.WithSampler(sdktrace.ParentBased(sdktrace.TraceIDRatioBased(sampleRatio))),
	)
	otel.SetTracerProvider(tp)
	return tp.Shutdown, nil
}
