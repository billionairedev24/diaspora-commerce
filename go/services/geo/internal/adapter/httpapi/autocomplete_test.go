package httpapi_test

import (
	"context"
	"encoding/json"
	"net/http"
	"testing"
)

func get(t *testing.T, url string, header http.Header) (int, http.Header, map[string]any) {
	t.Helper()
	req, err := http.NewRequestWithContext(context.Background(), http.MethodGet, url, nil)
	if err != nil {
		t.Fatal(err)
	}
	for k, v := range header {
		req.Header[k] = v
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = res.Body.Close() }()
	var out map[string]any
	_ = json.NewDecoder(res.Body).Decode(&out)
	return res.StatusCode, res.Header, out
}

// S139: the autocomplete API carries everything every address field needs.
func TestAutocompleteAPI(t *testing.T) {
	srv := server(t)
	status, h, body := get(t, srv.URL+"/v1/addresses/autocomplete?q=7&kind=postal_code&near=29.76,-95.37&radiusMiles=20", nil)
	if status != http.StatusOK || h.Get("Cache-Control") != "no-store" {
		t.Fatalf("status %d, cache %q", status, h.Get("Cache-Control"))
	}
	sugs, _ := body["suggestions"].([]any)
	session, _ := body["session"].(string)
	if len(sugs) == 0 || session == "" {
		t.Fatalf("suggestions and an issued session expected: %v", body)
	}
	first := sugs[0].(map[string]any)
	if first["kind"] != "postal_code" || first["distanceMeters"] == nil {
		t.Fatalf("first suggestion %v", first)
	}
	// The client's session comes back unchanged; lat/lng work as well as near.
	_, _, body = get(t, srv.URL+"/v1/addresses/autocomplete?q=1&session=abcdefgh-1234&lat=40.65&lng=-73.95",
		http.Header{"Accept-Language": {"fr-CA,fr;q=0.8"}})
	if body["session"] != "abcdefgh-1234" {
		t.Fatalf("session %v", body["session"])
	}
	for _, bad := range []string{"near=91,0", "near=abc", "radiusMiles=500&near=1,1", "kind=street", "session=x", "country=usa"} {
		if status, _, body := get(t, srv.URL+"/v1/addresses/autocomplete?q=1&"+bad, nil); status != http.StatusBadRequest {
			t.Errorf("%s: status %d %v", bad, status, body)
		}
	}
	// Empty input: no provider call, but a session to start with.
	if status, _, body := get(t, srv.URL+"/v1/addresses/autocomplete?q=", nil); status != http.StatusOK ||
		len(body["suggestions"].([]any)) != 0 || body["session"] == "" {
		t.Fatalf("empty input: %d %v", status, body)
	}
}
