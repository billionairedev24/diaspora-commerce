package httpserver

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestStatusRecorderFlushesAndUnwraps(t *testing.T) {
	inner := httptest.NewRecorder()
	rec := &statusRecorder{ResponseWriter: inner, status: http.StatusOK}

	var w http.ResponseWriter = rec
	f, ok := w.(http.Flusher)
	if !ok {
		t.Fatal("statusRecorder must implement http.Flusher")
	}
	rec.WriteHeader(http.StatusAccepted)
	rec.WriteHeader(http.StatusTeapot) // superfluous: the first status wins
	_, _ = io.WriteString(rec, "data: hi\n\n")
	f.Flush()
	if !inner.Flushed {
		t.Fatal("Flush did not reach the underlying writer")
	}
	if rec.status != http.StatusAccepted {
		t.Fatalf("status = %d, want 202", rec.status)
	}
	if rec.Unwrap() != inner {
		t.Fatal("Unwrap must return the wrapped writer")
	}
	if err := http.NewResponseController(rec).Flush(); err != nil {
		t.Fatalf("ResponseController.Flush through the recorder: %v", err)
	}
}

func TestStatusRecorderReportsUnsupportedFlush(t *testing.T) {
	rec := &statusRecorder{ResponseWriter: plainWriter{h: http.Header{}}, status: http.StatusOK}
	rec.Flush() // must not panic
	if err := rec.FlushError(); err == nil {
		t.Fatal("FlushError should report that the writer beneath cannot flush")
	}
}

type plainWriter struct{ h http.Header }

func (p plainWriter) Header() http.Header       { return p.h }
func (plainWriter) Write(b []byte) (int, error) { return len(b), nil }
func (plainWriter) WriteHeader(int)             {}

// sse writes n events, one every gap, flushing each one.
func sse(n int, gap time.Duration) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(http.StatusOK)
		for i := range n {
			if _, err := fmt.Fprintf(w, "data: %d\n\n", i); err != nil {
				return
			}
			if err := http.NewResponseController(w).Flush(); err != nil {
				return
			}
			select {
			case <-time.After(gap):
			case <-r.Context().Done():
				return
			}
		}
	}
}

func newTestServer(t *testing.T, opts Options, register func(*http.ServeMux)) *httptest.Server {
	t.Helper()
	opts.Name = "test"
	opts.Logger = slog.New(slog.DiscardHandler)
	s, ok := New(opts).(*serverImpl)
	if !ok {
		t.Fatal("New must return *serverImpl")
	}
	register(s.Mux())
	ts := httptest.NewUnstartedServer(nil)
	ts.Config = s.httpServer(s.handler())
	ts.Start()
	t.Cleanup(ts.Close)
	return ts
}

func countEvents(t *testing.T, url string) int {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, http.NoBody)
	if err != nil {
		t.Fatal(err)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		return 0
	}
	defer func() { _ = res.Body.Close() }()
	n := 0
	sc := bufio.NewScanner(res.Body)
	for sc.Scan() {
		if strings.HasPrefix(sc.Text(), "data: ") {
			n++
		}
	}
	return n
}

func TestStreamRoutesAreExemptFromWriteTimeout(t *testing.T) {
	const events, gap = 6, 60 * time.Millisecond // ~360ms of streaming against a 150ms write timeout
	ts := newTestServer(t, Options{WriteTimeout: 150 * time.Millisecond, StreamRoutes: []string{"GET /sse"}},
		func(m *http.ServeMux) {
			m.Handle("GET /sse", sse(events, gap))
			m.Handle("GET /wrapped", Stream(sse(events, gap)))
			m.Handle("GET /plain", sse(events, gap))
		})

	if got := countEvents(t, ts.URL+"/sse"); got != events {
		t.Fatalf("StreamRoutes route: got %d events, want %d", got, events)
	}
	if got := countEvents(t, ts.URL+"/wrapped"); got != events {
		t.Fatalf("Stream-wrapped route: got %d events, want %d", got, events)
	}
	if got := countEvents(t, ts.URL+"/plain"); got >= events {
		t.Fatalf("ordinary route should be cut by the write timeout, got all %d events", got)
	}
}

func TestStreamForCapsTheStream(t *testing.T) {
	ts := newTestServer(t, Options{WriteTimeout: 100 * time.Millisecond}, func(m *http.ServeMux) {
		m.Handle("GET /capped", StreamFor(250*time.Millisecond, sse(20, 50*time.Millisecond)))
	})
	got := countEvents(t, ts.URL+"/capped")
	if got < 3 || got >= 20 {
		t.Fatalf("capped stream: got %d events, want more than the write timeout allows and fewer than all", got)
	}
}

func TestStreamFlushesEachEventBeforeTheHandlerReturns(t *testing.T) {
	release := make(chan struct{})
	ts := newTestServer(t, Options{StreamRoutes: []string{"GET /sse"}}, func(m *http.ServeMux) {
		m.HandleFunc("GET /sse", func(w http.ResponseWriter, _ *http.Request) {
			w.Header().Set("Content-Type", "text/event-stream")
			_, _ = io.WriteString(w, "data: first\n\n")
			if f, ok := w.(http.Flusher); ok {
				f.Flush()
			}
			<-release
		})
	})
	defer close(release)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, ts.URL+"/sse", http.NoBody)
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = res.Body.Close() }()
	line, err := bufio.NewReader(res.Body).ReadString('\n')
	if err != nil || line != "data: first\n" {
		t.Fatalf("first event not flushed: %q, %v", line, err)
	}
}
