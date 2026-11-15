// Package modcfg resolves configuration for the modules of afrotransact-go.
//
// Several Go services read the same names (PORT, DB_URL, REDIS_URL, KAFKA_GROUP), so a module's lookup tries
// <MODULE>_<NAME> first (CART_DB_URL, GATEWAY_BFF_REDIS_URL) and then <NAME>. On top of that, the URL of a module that runs
// in this process points at it in-process (SEARCH_URL becomes http://search.inproc) unless the operator set
// <MODULE>_<NAME> for that one edge, and a module's database URL can be derived from one shared Go database (AT_GO_DB_URL,
// one schema per module).
package modcfg

import (
	"fmt"
	"net/url"
	"strings"
)

// Lookup resolves one configuration name.
type Lookup func(name string) (string, bool)

// Prefix is the environment prefix of a module: gateway-bff becomes GATEWAY_BFF_.
func Prefix(module string) string {
	return strings.ToUpper(strings.ReplaceAll(module, "-", "_")) + "_"
}

// Options describe how a lookup is composed for one module.
type Options struct {
	Module string
	// Base is the process environment (os.LookupEnv).
	Base Lookup
	// InProcess maps a configuration name (SEARCH_URL) to its in-process value (http://search.inproc). It applies
	// unless <MODULE>_<NAME> is set.
	InProcess map[string]string
	// SharedDBURL is AT_GO_DB_URL; with Schema it supplies DB_URL for modules that set neither <MODULE>_DB_URL nor DB_URL.
	SharedDBURL string
	Schema      string
}

// New composes the lookup for one module.
func New(o Options) Lookup {
	prefix := Prefix(o.Module)
	lookup := func(name string) (string, bool) {
		if v, ok := o.Base(prefix + name); ok && v != "" {
			return v, true
		}
		if v, ok := o.InProcess[name]; ok {
			return v, true
		}
		if v, ok := o.Base(name); ok && v != "" {
			return v, true
		}
		if name == "DB_URL" && o.SharedDBURL != "" && o.Schema != "" {
			if u, err := SchemaURL(o.SharedDBURL, o.Schema); err == nil {
				return u, true
			}
		}
		return "", false
	}
	return func(name string) (string, bool) {
		v, ok := lookup(name)
		if !ok || name != "DB_URL" {
			return v, ok
		}
		// <MODULE>_DB_MAX_CONNS (written by the Helm chart) sets the module's pool maximum; the modules also cap it themselves.
		if n, set := o.Base(prefix + "DB_MAX_CONNS"); set && strings.TrimSpace(n) != "" {
			if u, err := withParam(v, "pool_max_conns", strings.TrimSpace(n)); err == nil {
				return u, true
			}
		}
		return v, ok
	}
}

func withParam(dsn, key, value string) (string, error) {
	u, err := url.Parse(dsn)
	if err != nil {
		return "", fmt.Errorf("modcfg: parse database url: %w", err)
	}
	q := u.Query()
	if q.Get(key) == "" {
		q.Set(key, value)
	}
	u.RawQuery = q.Encode()
	return u.String(), nil
}

// SchemaURL pins a module to its schema in the shared database: it adds search_path=<schema> to the connection URL
// (pgx sends it as a startup runtime parameter, so every pooled connection starts with it). A schema list such as
// "geo,public" keeps PostGIS' types, installed by the admin in public, visible.
func SchemaURL(dsn, schema string) (string, error) {
	u, err := url.Parse(dsn)
	if err != nil {
		return "", fmt.Errorf("modcfg: parse database url: %w", err)
	}
	q := u.Query()
	q.Set("search_path", schema)
	u.RawQuery = q.Encode()
	return u.String(), nil
}
