package auth

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// Verifier validates a bearer token and returns the actor it represents.
type Verifier interface {
	Verify(ctx context.Context, token string) (Actor, error)
}

// ErrUnauthenticated is returned for missing, expired or invalid tokens.
var ErrUnauthenticated = errors.New("unauthenticated")

type jwksVerifier struct {
	issuer   string
	jwksURL  string
	audience string
	client   *http.Client
	ttl      time.Duration

	mu      sync.RWMutex
	keys    map[string]any
	fetched time.Time
}

var _ Verifier = (*jwksVerifier)(nil)

// NewJWKSVerifier verifies RS256/ES256 tokens issued by issuer against its JWKS (cached for 10 minutes,
// refreshed early when an unknown key id appears, which covers key rotation).
func NewJWKSVerifier(issuer, audience string, client *http.Client) Verifier {
	return &jwksVerifier{
		issuer:   strings.TrimSuffix(issuer, "/"),
		jwksURL:  strings.TrimSuffix(issuer, "/") + "/oauth2/jwks",
		audience: audience,
		client:   client,
		ttl:      10 * time.Minute,
		keys:     map[string]any{},
	}
}

type claims struct {
	jwt.RegisteredClaims
	PrincipalType string   `json:"pt"`
	Org           string   `json:"org"`
	Roles         []string `json:"roles"`
	Perms         []string `json:"perms"`
	Scope         string   `json:"scope"`
}

func (v *jwksVerifier) Verify(ctx context.Context, raw string) (Actor, error) {
	var c claims
	opts := []jwt.ParserOption{jwt.WithIssuer(v.issuer), jwt.WithValidMethods([]string{"RS256", "ES256"}), jwt.WithLeeway(30 * time.Second)}
	if v.audience != "" {
		opts = append(opts, jwt.WithAudience(v.audience))
	}
	_, err := jwt.ParseWithClaims(raw, &c, func(t *jwt.Token) (any, error) {
		kid, _ := t.Header["kid"].(string)
		return v.key(ctx, kid)
	}, opts...)
	if err != nil {
		return Actor{}, fmt.Errorf("%w: %w", ErrUnauthenticated, err)
	}
	perms := append([]string{}, c.Perms...)
	if c.Scope != "" {
		perms = append(perms, strings.Fields(c.Scope)...)
	}
	pt := c.PrincipalType
	if pt == "" {
		pt = "CUSTOMER"
	}
	return Actor{Subject: c.Subject, Type: strings.ToUpper(pt), OrgID: c.Org, Roles: c.Roles, Permissions: perms}, nil
}

func (v *jwksVerifier) key(ctx context.Context, kid string) (any, error) {
	v.mu.RLock()
	k, ok := v.keys[kid]
	fresh := time.Since(v.fetched) < v.ttl
	v.mu.RUnlock()
	if ok && fresh {
		return k, nil
	}
	if err := v.refresh(ctx); err != nil {
		return nil, err
	}
	v.mu.RLock()
	defer v.mu.RUnlock()
	if k, ok := v.keys[kid]; ok {
		return k, nil
	}
	return nil, fmt.Errorf("unknown key id %q", kid)
}

type jwk struct {
	Kid string `json:"kid"`
	Kty string `json:"kty"`
	N   string `json:"n"`
	E   string `json:"e"`
	Crv string `json:"crv"`
	X   string `json:"x"`
	Y   string `json:"y"`
}

func (v *jwksVerifier) refresh(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, v.jwksURL, nil)
	if err != nil {
		return fmt.Errorf("jwks request: %w", err)
	}
	resp, err := v.client.Do(req)
	if err != nil {
		return fmt.Errorf("jwks fetch: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("jwks fetch: status %d", resp.StatusCode)
	}
	var set struct {
		Keys []jwk `json:"keys"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&set); err != nil {
		return fmt.Errorf("jwks decode: %w", err)
	}
	keys := make(map[string]any, len(set.Keys))
	for _, k := range set.Keys {
		pub, err := k.publicKey()
		if err != nil {
			continue
		}
		keys[k.Kid] = pub
	}
	v.mu.Lock()
	v.keys, v.fetched = keys, time.Now()
	v.mu.Unlock()
	return nil
}

func (k jwk) publicKey() (any, error) {
	dec := base64.RawURLEncoding
	switch k.Kty {
	case "RSA":
		n, err := dec.DecodeString(k.N)
		if err != nil {
			return nil, fmt.Errorf("rsa n: %w", err)
		}
		e, err := dec.DecodeString(k.E)
		if err != nil {
			return nil, fmt.Errorf("rsa e: %w", err)
		}
		return &rsa.PublicKey{N: new(big.Int).SetBytes(n), E: int(new(big.Int).SetBytes(e).Int64())}, nil
	case "EC":
		if k.Crv != "P-256" {
			return nil, fmt.Errorf("unsupported curve %s", k.Crv)
		}
		x, err := dec.DecodeString(k.X)
		if err != nil {
			return nil, fmt.Errorf("ec x: %w", err)
		}
		y, err := dec.DecodeString(k.Y)
		if err != nil {
			return nil, fmt.Errorf("ec y: %w", err)
		}
		if len(x) > 32 || len(y) > 32 {
			return nil, errors.New("ec coordinates too long")
		}
		point := make([]byte, 65)
		point[0] = 4
		copy(point[33-len(x):33], x)
		copy(point[65-len(y):], y)
		pub, err := ecdsa.ParseUncompressedPublicKey(elliptic.P256(), point)
		if err != nil {
			return nil, fmt.Errorf("ec key: %w", err)
		}
		return pub, nil
	default:
		return nil, fmt.Errorf("unsupported key type %s", k.Kty)
	}
}
