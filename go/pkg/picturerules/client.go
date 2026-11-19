package picturerules

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"
)

// Source gives the rules in force. It never fails: callers on the request path always get usable rules.
type Source interface {
	// Rules returns the picture rules in force now.
	Rules(ctx context.Context) Rules
	// Invalidate drops the cache so the next call reads the setting again (after a staff change).
	Invalidate()
}

type staticSource struct{ rules Rules }

// Static is a Source that always returns the same rules (tests, and services that do not reach catalog).
func Static(r Rules) Source { return staticSource{rules: r} }

func (s staticSource) Rules(context.Context) Rules { return s.rules }
func (s staticSource) Invalidate()                 {}

// DefaultTTL is how long a read of the setting is reused: a staff change reaches every service within this time.
const DefaultTTL = 30 * time.Second

// Doer is the HTTP client used to read the setting (service calls carry their own token).
type Doer interface {
	Do(*http.Request) (*http.Response, error)
}

type clientImpl struct {
	url      string
	http     Doer
	ttl      time.Duration
	fallback Rules
	now      func() time.Time

	mu      sync.Mutex
	current Rules
	at      time.Time
}

var _ Source = (*clientImpl)(nil)

// NewClient reads GET {baseURL}/v1/public/picture-rules from catalog-service. fallback is the environment's default
// profile (PICTURE_RULES_PROFILE: local and dev testing, uat and prod startup); it is used until the first successful read
// and whenever catalog cannot be reached and nothing was read yet. After a successful read the last good rules are kept
// while catalog is down.
func NewClient(baseURL string, httpClient Doer, ttl time.Duration, fallback Rules) Source {
	if ttl <= 0 {
		ttl = DefaultTTL
	}
	if httpClient == nil {
		httpClient = &http.Client{Timeout: 3 * time.Second}
	}
	return &clientImpl{url: strings.TrimRight(baseURL, "/") + "/v1/public/picture-rules", http: httpClient, ttl: ttl,
		fallback: fallback, now: time.Now, current: fallback}
}

func (c *clientImpl) Rules(ctx context.Context) Rules {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.at.IsZero() && c.now().Sub(c.at) < c.ttl {
		return c.current
	}
	r, err := c.fetch(ctx)
	c.at = c.now()
	if err == nil {
		c.current = r
	}
	return c.current
}

func (c *clientImpl) Invalidate() {
	c.mu.Lock()
	c.at = time.Time{}
	c.mu.Unlock()
}

func (c *clientImpl) fetch(ctx context.Context) (Rules, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.url, nil)
	if err != nil {
		return Rules{}, fmt.Errorf("picture rules request: %w", err)
	}
	req.Header.Set("Accept", "application/json")
	res, err := c.http.Do(req)
	if err != nil {
		return Rules{}, fmt.Errorf("picture rules: %w", err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.StatusCode != http.StatusOK {
		return Rules{}, fmt.Errorf("picture rules: status %d", res.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(res.Body, 64<<10))
	if err != nil {
		return Rules{}, fmt.Errorf("picture rules body: %w", err)
	}
	return Parse(body)
}

// Parse reads the wire document and validates it; an unknown profile or invalid knobs are an error, never guessed.
func Parse(body []byte) (Rules, error) {
	var r Rules
	if err := json.Unmarshal(body, &r); err != nil {
		return Rules{}, fmt.Errorf("picture rules json: %w", err)
	}
	if _, ok := ParseProfile(string(r.Profile)); !ok {
		return Rules{}, fmt.Errorf("picture rules: unknown profile %q", r.Profile)
	}
	if p := r.Knobs.Problem(); p != "" {
		return Rules{}, fmt.Errorf("picture rules: %s", p)
	}
	if r.Overridden == nil {
		r.Overridden = []string{}
	}
	r.Formats = Formats()
	return r, nil
}

// FromEnv builds the fallback rules for an environment from APP_ENV and an optional PICTURE_RULES_PROFILE: local and dev
// default to testing, uat and prod to startup (the first-launch decision; one line to change per environment in
// application-<env>.yml of catalog and in config/<env>.yaml of the Go services).
func FromEnv(appEnv, profile string) Rules {
	if p, ok := ParseProfile(profile); ok {
		return Default(p)
	}
	return Default(EnvDefaultProfile(appEnv))
}

// EnvDefaultProfile is the initial profile of an environment.
func EnvDefaultProfile(appEnv string) Profile {
	switch strings.ToLower(strings.TrimSpace(appEnv)) {
	case "uat", "prod":
		return Startup
	default:
		return Testing
	}
}
