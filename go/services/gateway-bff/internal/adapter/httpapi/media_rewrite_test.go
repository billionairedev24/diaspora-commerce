package httpapi

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
)

func serveRewritten(t *testing.T, o SecurityOptions, h http.HandlerFunc) *httptest.ResponseRecorder {
	rec := httptest.NewRecorder()
	rewriteMedia(o)(h).ServeHTTP(rec, httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/v1/home", nil))
	return rec
}

func TestRewriteMediaReplacesTheLocalMinioPrefixInJSON(t *testing.T) {
	o := SecurityOptions{MediaRewriteFrom: "http://localhost:9000/media/", MediaRewriteTo: "http://192.168.1.50:9000/media/"}
	rec := serveRewritten(t, o, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Content-Length", "999")
		w.WriteHeader(http.StatusCreated)
		_, _ = io.WriteString(w, `{"image":"http://localhost:9000/media/p/1.jpg",`)
		_, _ = io.WriteString(w, `"other":"http://localhost:8200/v1"}`)
	})
	want := `{"image":"http://192.168.1.50:9000/media/p/1.jpg","other":"http://localhost:8200/v1"}`
	if rec.Code != http.StatusCreated || rec.Body.String() != want {
		t.Fatalf("got %d %s", rec.Code, rec.Body.String())
	}
	if rec.Header().Get("Content-Length") != strconv.Itoa(len(want)) {
		t.Fatalf("content-length %q", rec.Header().Get("Content-Length"))
	}
}

func TestRewriteMediaLeavesOtherBodiesAndUnsetConfigAlone(t *testing.T) {
	body := `data: http://localhost:9000/media/x` + "\n\n"
	stream := func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = io.WriteString(w, body)
	}
	o := SecurityOptions{MediaRewriteFrom: "http://localhost:9000/media/", MediaRewriteTo: "http://10.0.0.5:9000/media/"}
	if rec := serveRewritten(t, o, stream); rec.Body.String() != body {
		t.Fatalf("stream changed: %q", rec.Body.String())
	}
	json := func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"u":"http://localhost:9000/media/a"}`)
	}
	if rec := serveRewritten(t, SecurityOptions{}, json); rec.Body.String() != `{"u":"http://localhost:9000/media/a"}` {
		t.Fatalf("rewrote without config: %s", rec.Body.String())
	}
}
