// Package openfga is the OpenFGA HTTP API adapter (tuple writes and reads, store and model provisioning).
package openfga

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"reflect"
	"strings"
	"sync"

	"github.com/billionairedev24/diaspora-commerce/go/services/authz-sync/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/authz-sync/internal/port"
)

// Client is both the tuple store and the model admin.
type Client interface {
	port.TupleStore
	port.ModelAdmin
	// UseStore pins the store id used by tuple operations.
	UseStore(id string)
}

type clientImpl struct {
	base  string
	token string
	http  *http.Client

	mu      sync.RWMutex
	storeID string
}

var _ Client = (*clientImpl)(nil)

// NewClient talks to the OpenFGA HTTP API at baseURL; token is a pre-shared key (empty when authn is off).
func NewClient(baseURL, token, storeID string, httpClient *http.Client) Client {
	return &clientImpl{base: strings.TrimRight(baseURL, "/"), token: token, http: httpClient, storeID: storeID}
}

func (c *clientImpl) UseStore(id string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.storeID = id
}

func (c *clientImpl) store() (string, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	if c.storeID == "" {
		return "", errors.New("openfga: no store selected")
	}
	return c.storeID, nil
}

type tupleKeys struct {
	TupleKeys   []domain.Tuple `json:"tuple_keys"`
	OnDuplicate string         `json:"on_duplicate,omitempty"`
	OnMissing   string         `json:"on_missing,omitempty"`
}

type writeRequest struct {
	Writes  *tupleKeys `json:"writes,omitempty"`
	Deletes *tupleKeys `json:"deletes,omitempty"`
}

// Write applies the change in one transaction; duplicates and missing deletes are ignored (idempotent replays).
func (c *clientImpl) Write(ctx context.Context, change domain.Change) error {
	if change.Empty() {
		return nil
	}
	store, err := c.store()
	if err != nil {
		return err
	}
	var req writeRequest
	if len(change.Writes) > 0 {
		req.Writes = &tupleKeys{TupleKeys: change.Writes, OnDuplicate: "ignore"}
	}
	if len(change.Deletes) > 0 {
		req.Deletes = &tupleKeys{TupleKeys: change.Deletes, OnMissing: "ignore"}
	}
	return c.do(ctx, http.MethodPost, "/stores/"+store+"/write", req, nil)
}

type readResponse struct {
	Tuples []struct {
		Key domain.Tuple `json:"key"`
	} `json:"tuples"`
	ContinuationToken string `json:"continuation_token"`
}

// Read returns every tuple on object with relation, following continuation tokens.
func (c *clientImpl) Read(ctx context.Context, object, relation string) ([]domain.Tuple, error) {
	store, err := c.store()
	if err != nil {
		return nil, err
	}
	var out []domain.Tuple
	token := ""
	for {
		req := map[string]any{"tuple_key": map[string]string{"object": object, "relation": relation}}
		if token != "" {
			req["continuation_token"] = token
		}
		var resp readResponse
		if err := c.do(ctx, http.MethodPost, "/stores/"+store+"/read", req, &resp); err != nil {
			return nil, err
		}
		for _, t := range resp.Tuples {
			out = append(out, t.Key)
		}
		if resp.ContinuationToken == "" {
			return out, nil
		}
		token = resp.ContinuationToken
	}
}

// Ping checks the server is up (unauthenticated /healthz).
func (c *clientImpl) Ping(ctx context.Context) error {
	return c.do(ctx, http.MethodGet, "/healthz", nil, nil)
}

type storesResponse struct {
	Stores []struct {
		ID   string `json:"id"`
		Name string `json:"name"`
	} `json:"stores"`
}

// EnsureStore finds the store by name or creates it.
func (c *clientImpl) EnsureStore(ctx context.Context, name string) (string, error) {
	var list storesResponse
	if err := c.do(ctx, http.MethodGet, "/stores?name="+url.QueryEscape(name), nil, &list); err != nil {
		return "", err
	}
	for _, s := range list.Stores {
		if s.Name == name {
			return s.ID, nil
		}
	}
	var created struct {
		ID string `json:"id"`
	}
	if err := c.do(ctx, http.MethodPost, "/stores", map[string]string{"name": name}, &created); err != nil {
		return "", err
	}
	return created.ID, nil
}

// EnsureModel writes model unless the store's latest model is equivalent (ignoring ids and empty fields).
func (c *clientImpl) EnsureModel(ctx context.Context, model []byte) (string, bool, error) {
	store, err := c.store()
	if err != nil {
		return "", false, err
	}
	var want map[string]any
	if err := json.Unmarshal(model, &want); err != nil {
		return "", false, fmt.Errorf("openfga: model json: %w", err)
	}
	var latest struct {
		Models []map[string]any `json:"authorization_models"`
	}
	if err := c.do(ctx, http.MethodGet, "/stores/"+store+"/authorization-models?page_size=1", nil, &latest); err != nil {
		return "", false, err
	}
	if len(latest.Models) > 0 && Equivalent(latest.Models[0], want) {
		id, _ := latest.Models[0]["id"].(string)
		return id, false, nil
	}
	var written struct {
		ID string `json:"authorization_model_id"`
	}
	if err := c.do(ctx, http.MethodPost, "/stores/"+store+"/authorization-models", json.RawMessage(model),
		&written); err != nil {
		return "", false, err
	}
	return written.ID, true, nil
}

// Equivalent compares two authorization models, ignoring the id and fields that are null, empty or absent.
func Equivalent(a, b map[string]any) bool {
	strip := func(m map[string]any) any {
		c := make(map[string]any, len(m))
		for k, v := range m {
			if k != "id" {
				c[k] = v
			}
		}
		return prune(c)
	}
	return reflect.DeepEqual(strip(a), strip(b))
}

func prune(v any) any {
	switch t := v.(type) {
	case map[string]any:
		out := make(map[string]any, len(t))
		for k, val := range t {
			if p := prune(val); p != nil {
				out[k] = p
			}
		}
		if len(out) == 0 {
			return nil
		}
		return out
	case []any:
		out := make([]any, 0, len(t))
		for _, val := range t {
			if p := prune(val); p != nil {
				out = append(out, p)
			}
		}
		if len(out) == 0 {
			return nil
		}
		return out
	case string:
		if t == "" {
			return nil
		}
		return t
	default:
		return v
	}
}

func (c *clientImpl) do(ctx context.Context, method, path string, body, out any) error {
	var reader io.Reader
	if body != nil {
		buf, err := json.Marshal(body)
		if err != nil {
			return fmt.Errorf("openfga: encode: %w", err)
		}
		reader = bytes.NewReader(buf)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.base+path, reader)
	if err != nil {
		return fmt.Errorf("openfga: request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return fmt.Errorf("openfga %s %s: %w", method, path, err)
	}
	defer func() { _ = resp.Body.Close() }()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return fmt.Errorf("openfga %s %s: read: %w", method, path, err)
	}
	switch {
	case resp.StatusCode >= 200 && resp.StatusCode < 300:
	case resp.StatusCode >= 400 && resp.StatusCode < 500 && resp.StatusCode != http.StatusTooManyRequests &&
		resp.StatusCode != http.StatusUnauthorized:
		return fmt.Errorf("openfga %s %s: %d %s: %w", method, path, resp.StatusCode, raw, port.ErrRejected)
	default:
		return fmt.Errorf("openfga %s %s: %d %s", method, path, resp.StatusCode, raw)
	}
	if out == nil || len(raw) == 0 {
		return nil
	}
	if err := json.Unmarshal(raw, out); err != nil {
		return fmt.Errorf("openfga %s %s: decode: %w", method, path, err)
	}
	return nil
}
