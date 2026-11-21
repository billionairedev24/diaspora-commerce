package config

import (
	"errors"

	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
)

// Validate refuses a configuration that must not run in the given environment. In uat and prod a Google Maps key
// (GOOGLE_PLACES_API_KEY, else GOOGLE_MAPS_API_KEY) is required: Google is the only address validator, and without a
// key every address would be accepted unchecked, which is for local and offline development only.
func (c Config) Validate(env string) error {
	if env != pkgconfig.EnvUAT && env != pkgconfig.EnvProd {
		return nil
	}
	if c.GoogleKey() == "" {
		return errors.New("GOOGLE_PLACES_API_KEY (or GOOGLE_MAPS_API_KEY) is not set: " + env +
			" validates addresses with Google Maps and refuses to start without a key")
	}
	return nil
}
