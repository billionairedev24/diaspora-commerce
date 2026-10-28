// Package config is the geo service configuration (config/<APP_ENV>.yaml + env overrides).
package config

import "time"

// Config for geo.
type Config struct {
	Port        int    `yaml:"port" env:"PORT"`
	DocsEnabled bool   `yaml:"docsEnabled" env:"DOCS_ENABLED"`
	DatabaseURL string `yaml:"databaseUrl" env:"DB_URL"`
	// Providers is the autocomplete chain used when no Google key is configured (osm-nominatim, fixture). With a key,
	// Google Places (New) is the only autocomplete provider: see AddressProviders.
	Providers []string `yaml:"providers" env:"GEO_PROVIDERS"`
	// ReverseProviders in priority order: google-geocoding, osm-nominatim, fixture. With a key, google-geocoding
	// leads (see ReverseGeocoders); without one it is skipped.
	ReverseProviders []string `yaml:"reverseProviders" env:"GEO_REVERSE_PROVIDERS"`
	Google           struct {
		// APIKey is GOOGLE_PLACES_API_KEY; GOOGLE_MAPS_API_KEY (Routing.GoogleAPIKey) is used when it is blank.
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

// Provider names.
const (
	ProviderGooglePlaces    = "google-places"
	ProviderGoogleGeocoding = "google-geocoding"
	ProviderNominatim       = "osm-nominatim"
	ProviderFixture         = "fixture"
)

// GoogleKey is the Google Maps Platform key for Places (New) and Geocoding: GOOGLE_PLACES_API_KEY, else
// GOOGLE_MAPS_API_KEY (one key with Places API (New), Geocoding API and Routes API enabled serves every feature).
func (c Config) GoogleKey() string {
	if c.Google.APIKey != "" {
		return c.Google.APIKey
	}
	return c.Routing.GoogleAPIKey
}

// AddressProviders is the autocomplete chain. With a Google key, Google Places (New) Autocomplete + Place Details is
// the primary and only provider: its coverage is what the shopper expects, and mixing in Nominatim answers would
// split one billable session. Without a key the configured providers answer (Nominatim, fixtures), google-places
// skipped.
func (c Config) AddressProviders() []string {
	if c.GoogleKey() != "" {
		return []string{ProviderGooglePlaces}
	}
	out := make([]string, 0, len(c.Providers))
	for _, p := range c.Providers {
		if p != ProviderGooglePlaces {
			out = append(out, p)
		}
	}
	return out
}

// ReverseGeocoders is the reverse-geocoding chain: google-geocoding first when a key is configured (then the
// configured fallbacks), the configured providers without it otherwise.
func (c Config) ReverseGeocoders() []string {
	out := make([]string, 0, len(c.ReverseProviders)+1)
	if c.GoogleKey() != "" {
		out = append(out, ProviderGoogleGeocoding)
	}
	for _, p := range c.ReverseProviders {
		if p != ProviderGoogleGeocoding {
			out = append(out, p)
		}
	}
	return out
}
