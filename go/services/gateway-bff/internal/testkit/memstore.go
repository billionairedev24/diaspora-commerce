// Package testkit holds test doubles shared by the BFF's tests: an in-memory state store and fake upstream
// services that behave like the real ones (catalog, search, inventory, cart, geo, identity with RS256 JWKS and
// PKCE, and optionally content and payments). Not used by production code.
package testkit

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/valkey"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// MemStore is an in-memory valkey.Store.
type MemStore struct {
	mu         sync.Mutex
	sessions   map[string][]byte
	locks      map[string]bool
	auth       map[string]port.AuthRequest
	challenges map[string]port.Challenge
	selection  map[string]map[string]bool
	counters   map[string]int
}

var _ valkey.Store = (*MemStore)(nil)

// NewMemStore returns an empty store.
func NewMemStore() *MemStore {
	return &MemStore{sessions: map[string][]byte{}, locks: map[string]bool{}, auth: map[string]port.AuthRequest{},
		challenges: map[string]port.Challenge{}, selection: map[string]map[string]bool{}, counters: map[string]int{}}
}

// Ping always succeeds.
func (m *MemStore) Ping(context.Context) error { return nil }

// Load returns a copy of a session.
func (m *MemStore) Load(_ context.Context, key string) (*domain.Session, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	b, ok := m.sessions[key]
	if !ok {
		return nil, nil
	}
	var s domain.Session
	if err := json.Unmarshal(b, &s); err != nil {
		return nil, fmt.Errorf("decode: %w", err)
	}
	return &s, nil
}

// Save stores a copy of a session.
func (m *MemStore) Save(_ context.Context, key string, s *domain.Session, _ time.Duration) error {
	b, err := json.Marshal(s)
	if err != nil {
		return fmt.Errorf("encode: %w", err)
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.sessions[key] = b
	return nil
}

// Delete removes a session.
func (m *MemStore) Delete(_ context.Context, key string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.sessions, key)
	return nil
}

// SessionKeys lists stored session keys (tests).
func (m *MemStore) SessionKeys() []string {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]string, 0, len(m.sessions))
	for k := range m.sessions {
		out = append(out, k)
	}
	return out
}

// Lock takes an exclusive lock.
func (m *MemStore) Lock(_ context.Context, key string, _ time.Duration) (func(), bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.locks[key] {
		return func() {}, false, nil
	}
	m.locks[key] = true
	return func() {
		m.mu.Lock()
		delete(m.locks, key)
		m.mu.Unlock()
	}, true, nil
}

// PutAuthRequest stores an OIDC request.
func (m *MemStore) PutAuthRequest(_ context.Context, state string, r port.AuthRequest, _ time.Duration) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.auth[state] = r
	return nil
}

// TakeAuthRequest returns and deletes an OIDC request.
func (m *MemStore) TakeAuthRequest(_ context.Context, state string) (*port.AuthRequest, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	r, ok := m.auth[state]
	if !ok {
		return nil, nil
	}
	delete(m.auth, state)
	return &r, nil
}

// PutChallenge stores a challenge.
func (m *MemStore) PutChallenge(_ context.Context, id string, c port.Challenge, _ time.Duration) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.challenges[id] = c
	return nil
}

// GetChallenge reads a challenge.
func (m *MemStore) GetChallenge(_ context.Context, id string) (*port.Challenge, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	c, ok := m.challenges[id]
	if !ok {
		return nil, nil
	}
	return &c, nil
}

// DeleteChallenge removes a challenge.
func (m *MemStore) DeleteChallenge(_ context.Context, id string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.challenges, id)
	return nil
}

// Deselected returns the deselected lines of an owner.
func (m *MemStore) Deselected(_ context.Context, owner string) (map[string]bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := map[string]bool{}
	for k := range m.selection[owner] {
		out[k] = true
	}
	return out, nil
}

// SetSelected marks lines selected or deselected.
func (m *MemStore) SetSelected(_ context.Context, owner string, atins []string, selected bool) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.selection[owner] == nil {
		m.selection[owner] = map[string]bool{}
	}
	for _, a := range atins {
		if selected {
			delete(m.selection[owner], a)
		} else {
			m.selection[owner][a] = true
		}
	}
	return nil
}

// Add is the daily counter: it counts per key (the day is ignored in tests).
func (m *MemStore) Add(_ context.Context, key string, n int) (int, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.counters["dc:"+key] += n
	return m.counters["dc:"+key], nil
}

// Allow counts calls per key (window ignored).
func (m *MemStore) Allow(_ context.Context, key string, limit int, window time.Duration) (bool, time.Duration, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.counters[key]++
	if m.counters[key] > limit {
		return false, window, nil
	}
	return true, 0, nil
}
