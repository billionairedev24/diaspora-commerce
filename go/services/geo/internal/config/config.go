// Package config is the geo service configuration (config/<APP_ENV>.yaml + env overrides).
package config

import "time"

// Config for geo.
type Config struct {
	Port        int    `yaml:"port" env:"PORT"`
	DocsEnabled bool   `yaml:"docsEnabled" env:"DOCS_ENABLED"`
	DatabaseURL string `yaml:"databaseUrl" env:"DB_URL"`
	// Providers in priority order: google-places, osm-nominatim, fixture. Google is skipped without a key.
	Providers []string `yaml:"providers" env:"GEO_PROVIDERS"`
	// ReverseProviders in priority order: google-geocoding, osm-nominatim, fixture. Google is skipped without a key.
	ReverseProviders []string `yaml:"reverseProviders" env:"GEO_REVERSE_PROVIDERS"`
	Google           struct {
		APIKey string `env:"GOOGLE_PLACES_API_KEY"`
	} `yaml:"google"`
	Nominatim struct {
		URL       string `yaml:"url" env:"NOMINATIM_URL"`
		UserAgent string `yaml:"userAgent" env:"NOMINATIM_USER_AGENT"`
	} `yaml:"nominatim"`
	// Routing (road distance and duration, S255): providers in priority order (google, osrm), each enabled only when
	// configured (GOOGLE_MAPS_API_KEY, else the Places key / OSRM_URL); the next is tried when one fails or times out.
	// PostGIS geodesic × DetourFactor is the last resort. Results are cached by ~CachePrecisionMeters for CacheTTL.
	Routing struct {
		Providers            []string      `yaml:"providers" env:"GEO_ROUTING_PROVIDERS"`
		GoogleAPIKey         string        `env:"GOOGLE_MAPS_API_KEY"`
		OSRMURL              string        `yaml:"osrmUrl" env:"OSRM_URL"`
		Timeout              time.Duration `yaml:"timeout" env:"GEO_ROUTING_TIMEOUT"`
		CacheTTL             time.Duration `yaml:"cacheTtl" env:"GEO_ROUTE_CACHE_TTL"`
		CachePrecisionMeters float64       `yaml:"cachePrecisionMeters" env:"GEO_ROUTE_CACHE_PRECISION_METERS"`
		CacheMaxEntries      int           `yaml:"cacheMaxEntries" env:"GEO_ROUTE_CACHE_MAX_ENTRIES"`
		DetourFactor         float64       `yaml:"detourFactor" env:"GEO_DETOUR_FACTOR"`
		FallbackSpeedMph     float64       `yaml:"fallbackSpeedMph" env:"GEO_FALLBACK_SPEED_MPH"`
	} `yaml:"routing"`
	Smarty struct {
		AuthID    string `env:"SMARTY_AUTH_ID"`
		AuthToken string `env:"SMARTY_AUTH_TOKEN"`
	} `yaml:"smarty"`
	Auth struct {
		Enabled  bool   `yaml:"enabled" env:"AUTH_ENABLED"`
		Issuer   string `yaml:"issuer" env:"AUTH_ISSUER_URL"`
		Audience string `yaml:"audience" env:"AUTH_AUDIENCE"`
	} `yaml:"auth"`
	Telemetry struct {
		OTLPEndpoint string  `yaml:"otlpEndpoint" env:"OTEL_EXPORTER_OTLP_ENDPOINT_HOST"`
		SampleRatio  float64 `yaml:"sampleRatio" env:"TRACE_SAMPLING"`
	} `yaml:"telemetry"`
}
