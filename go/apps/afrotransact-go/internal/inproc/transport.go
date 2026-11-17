// Package inproc serves HTTP requests to a Go module without a socket: a RoundTripper that recognises the host
// <module>.inproc, runs the request through that module's Handler (the same chain a network request would pass: recover,
// correlation id, metrics, tracing, authentication middleware) and returns the buffered response. Callers keep sending
// their bearer token, so the callee's auth middleware runs exactly as it does over HTTP.
package inproc

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"sync"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/module"
)

// HostSuffix marks the pseudo-host of an in-process module: http://cart.inproc.
const HostSuffix = ".inproc"

// URL is the base URL that reaches the named module in-process.
func URL(name string) string { return "http://" + name + HostSuffix }

// ErrModuleDown is returned for a call to a module that is not running in this process.
var ErrModuleDown = errors.New("inproc: module is not running in this process")

// Transport routes requests for <module>.inproc to registered handlers and everything else to Fallback.
type Transport interface {
	http.RoundTripper
	// Register makes a module reachable. Registering a name twice replaces the handler.
	Register(name string, h http.Handler)
}

type transportImpl struct {
	mu       sync.RWMutex
	handlers map[string]http.Handler
	fallback http.RoundTripper
}

var _ Transport = (*transportImpl)(nil)

// New returns a Transport. fallback serves every request that is not for an in-process module (nil means
// http.DefaultTransport at the time of the call).
func New(fallback http.RoundTripper) Transport {
	if fallback == nil {
		fallback = http.DefaultTransport
	}
	return &transportImpl{handlers: map[string]http.Handler{}, fallback: fallback}
}

func (t *transportImpl) Register(name string, h http.Handler) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.handlers[name] = h
}

func (t *transportImpl) RoundTrip(req *http.Request) (*http.Response, error) {
	host := req.URL.Hostname()
	name, ok := strings.CutSuffix(host, HostSuffix)
	if !ok {
		return t.fallback.RoundTrip(req) //nolint:wrapcheck // a RoundTripper hands the fallback transport's error back unchanged (callers match its types)
	}
	t.mu.RLock()
	h := t.handlers[name]
	t.mu.RUnlock()
	if h == nil {
		closeBody(req)
		return nil, fmt.Errorf("%w: %s", ErrModuleDown, name)
	}
	if err := req.Context().Err(); err != nil {
		closeBody(req)
		return nil, fmt.Errorf("inproc: %w", err)
	}
	// A server request: same method, URL, headers and body, marked with the callee so spans carry the right module.
	r := req.Clone(module.WithName(req.Context(), name))
	r.RequestURI = req.URL.RequestURI()
	r.RemoteAddr = "inproc"
	r.Host = req.URL.Host
	r.Proto, r.ProtoMajor, r.ProtoMinor = "HTTP/1.1", 1, 1
	if r.Body == nil {
		r.Body = http.NoBody
	}
	rec := &recorder{header: http.Header{}}
	h.ServeHTTP(rec, r)
	return rec.response(req), nil
}

func closeBody(req *http.Request) {
	if req.Body != nil {
		_ = req.Body.Close()
	}
}

// recorder buffers a response. Streams are not supported across modules (nothing in the Go modules streams to another
// Go module); Flush is accepted and ignored.
type recorder struct {
	header http.Header
	status int
	body   bytes.Buffer
}

func (r *recorder) Header() http.Header { return r.header }

func (r *recorder) WriteHeader(code int) {
	if r.status == 0 {
		r.status = code
	}
}

func (r *recorder) Write(b []byte) (int, error) {
	if r.status == 0 {
		r.status = http.StatusOK
	}
	return r.body.Write(b) //nolint:wrapcheck // io.Writer: bytes.Buffer.Write never fails, and the error would be passed through unchanged anyway
}

func (r *recorder) Flush() {}

func (r *recorder) response(req *http.Request) *http.Response {
	if r.status == 0 {
		r.status = http.StatusOK
	}
	h := r.header.Clone()
	if h.Get("Content-Type") == "" && r.body.Len() > 0 {
		h.Set("Content-Type", http.DetectContentType(r.body.Bytes()))
	}
	h.Set("Content-Length", strconv.Itoa(r.body.Len()))
	return &http.Response{
		Status: strconv.Itoa(r.status) + " " + http.StatusText(r.status), StatusCode: r.status,
		Proto: "HTTP/1.1", ProtoMajor: 1, ProtoMinor: 1,
		Header: h, Body: io.NopCloser(bytes.NewReader(r.body.Bytes())), ContentLength: int64(r.body.Len()), Request: req,
	}
}
