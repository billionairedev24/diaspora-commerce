// Package meili is a small Meilisearch REST client: JSON calls, task waiting and the error codes callers branch on.
// It uses the standard library only, so every service that talks to Meilisearch shares one dependency-free client.
package meili

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// Client talks to one Meilisearch instance.
type Client struct {
	base string
	key  string
	http *http.Client
	// PollEvery is how often Wait checks a task (default 20 ms).
	PollEvery time.Duration
}

// New returns a client for the instance at baseURL; apiKey is the master or an admin key (empty for an open dev instance).
func New(baseURL, apiKey string, h *http.Client) *Client {
	if h == nil {
		h = &http.Client{Timeout: 30 * time.Second}
	}
	return &Client{base: strings.TrimRight(baseURL, "/"), key: apiKey, http: h, PollEvery: 20 * time.Millisecond}
}

// Error is an error answer from Meilisearch.
type Error struct {
	Status  int
	Code    string `json:"code"`
	Message string `json:"message"`
}

func (e *Error) Error() string { return fmt.Sprintf("meilisearch %d %s: %s", e.Status, e.Code, e.Message) }

// IsCode reports whether err is a Meilisearch error with the given code (for example "index_not_found").
func IsCode(err error, code string) bool {
	var me *Error
	return errors.As(err, &me) && me.Code == code
}

// IsNotFound reports an unknown index or document.
func IsNotFound(err error) bool {
	return IsCode(err, "index_not_found") || IsCode(err, "document_not_found")
}

// Do sends one JSON request and decodes the answer into out (nil to discard).
func (c *Client) Do(ctx context.Context, method, path string, in, out any) error {
	var body io.Reader
	if in != nil {
		raw, err := json.Marshal(in)
		if err != nil {
			return fmt.Errorf("meilisearch: encode request: %w", err)
		}
		body = bytes.NewReader(raw)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.base+path, body)
	if err != nil {
		return fmt.Errorf("meilisearch: %w", err)
	}
	req.Header.Set("Accept", "application/json")
	if in != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if c.key != "" {
		req.Header.Set("Authorization", "Bearer "+c.key)
	}
	res, err := c.http.Do(req)
	if err != nil {
		return fmt.Errorf("meilisearch %s %s: %w", method, path, err)
	}
	defer func() { _ = res.Body.Close() }()
	raw, err := io.ReadAll(io.LimitReader(res.Body, 64<<20))
	if err != nil {
		return fmt.Errorf("meilisearch: read response: %w", err)
	}
	if res.StatusCode >= 300 {
		e := &Error{Status: res.StatusCode}
		if json.Unmarshal(raw, e) != nil || e.Code == "" {
			e.Code, e.Message = "unknown", strings.TrimSpace(string(raw))
		}
		return e
	}
	if out != nil && len(raw) > 0 {
		if err := json.Unmarshal(raw, out); err != nil {
			return fmt.Errorf("meilisearch: decode response: %w", err)
		}
	}
	return nil
}

type taskRef struct {
	TaskUID int64 `json:"taskUid"`
}

type taskState struct {
	Status string `json:"status"`
	Error  *Error `json:"error"`
}

// Task sends a write (which Meilisearch queues) and waits until it has been applied, so the next read sees it.
func (c *Client) Task(ctx context.Context, method, path string, in any) error {
	var ref taskRef
	if err := c.Do(ctx, method, path, in, &ref); err != nil {
		return err
	}
	return c.Wait(ctx, ref.TaskUID)
}

// Wait polls a task until it succeeds or fails.
func (c *Client) Wait(ctx context.Context, uid int64) error {
	every := c.PollEvery
	if every <= 0 {
		every = 20 * time.Millisecond
	}
	for {
		var st taskState
		if err := c.Do(ctx, http.MethodGet, "/tasks/"+url.PathEscape(fmt.Sprint(uid)), nil, &st); err != nil {
			return err
		}
		switch st.Status {
		case "succeeded":
			return nil
		case "failed", "canceled":
			if st.Error != nil {
				return st.Error
			}
			return fmt.Errorf("meilisearch task %d %s", uid, st.Status)
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(every):
		}
	}
}

// Health pings the instance.
func (c *Client) Health(ctx context.Context) error {
	var out struct {
		Status string `json:"status"`
	}
	if err := c.Do(ctx, http.MethodGet, "/health", nil, &out); err != nil {
		return err
	}
	if out.Status != "available" {
		return fmt.Errorf("meilisearch is %q", out.Status)
	}
	return nil
}

// Fetch lists documents of an index, optionally filtered (the index must declare the filtered attributes filterable).
func (c *Client) Fetch(ctx context.Context, index string, req FetchRequest) ([]map[string]any, error) {
	var out struct {
		Results []map[string]any `json:"results"`
	}
	if err := c.Do(ctx, http.MethodPost, "/indexes/"+url.PathEscape(index)+"/documents/fetch", req, &out); err != nil {
		return nil, err
	}
	return out.Results, nil
}

// FetchRequest is the body of POST /indexes/{uid}/documents/fetch.
type FetchRequest struct {
	Offset int      `json:"offset,omitempty"`
	Limit  int      `json:"limit"`
	Fields []string `json:"fields,omitempty"`
	Filter string   `json:"filter,omitempty"`
}

// Get returns one document, or (nil, nil) when it does not exist.
func (c *Client) Get(ctx context.Context, index, id string, fields ...string) (map[string]any, error) {
	path := "/indexes/" + url.PathEscape(index) + "/documents/" + url.PathEscape(id)
	if len(fields) > 0 {
		path += "?fields=" + url.QueryEscape(strings.Join(fields, ","))
	}
	var doc map[string]any
	if err := c.Do(ctx, http.MethodGet, path, nil, &doc); err != nil {
		if IsNotFound(err) {
			return nil, nil
		}
		return nil, err
	}
	return doc, nil
}

// Put merges documents into an index (existing documents keep the fields not sent) and waits.
func (c *Client) Put(ctx context.Context, index string, docs []map[string]any) error {
	if len(docs) == 0 {
		return nil
	}
	return c.Task(ctx, http.MethodPut, "/indexes/"+url.PathEscape(index)+"/documents?primaryKey=id", docs)
}

// Quote returns s as a string literal in a Meilisearch filter expression.
func Quote(s string) string {
	return `"` + strings.NewReplacer(`\`, `\\`, `"`, `\"`).Replace(s) + `"`
}
