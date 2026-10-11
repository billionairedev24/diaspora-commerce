package httpapi

import (
	"bytes"
	"net/http"
	"strconv"
	"strings"
)

// Local LAN mode only (make up MOBILE_HOST=lan, tools/dev/local.sh): product and content images were stored with
// this stack's MinIO address as the media service published it, http://localhost:9000/media/…. On a phone, localhost
// is the phone, so JSON responses get that prefix replaced by the LAN one (MediaRewriteFrom → MediaRewriteTo). The
// config only allows it with APP_ENV=local; every other environment serves its media CDN URLs untouched.
func rewriteMedia(o SecurityOptions) func(http.Handler) http.Handler {
	from, to := []byte(o.MediaRewriteFrom), []byte(o.MediaRewriteTo)
	return func(next http.Handler) http.Handler {
		if len(from) == 0 || len(to) == 0 || bytes.Equal(from, to) {
			return next
		}
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			rw := &rewriteWriter{ResponseWriter: w, status: http.StatusOK}
			next.ServeHTTP(rw, r)
			rw.finish(from, to)
		})
	}
}

// rewriteWriter buffers JSON bodies (to rewrite them once complete) and passes everything else straight through.
type rewriteWriter struct {
	http.ResponseWriter
	status  int
	decided bool
	json    bool
	buf     bytes.Buffer
}

func (w *rewriteWriter) decide() {
	if w.decided {
		return
	}
	w.decided = true
	w.json = strings.Contains(strings.ToLower(w.Header().Get("Content-Type")), "json")
}

func (w *rewriteWriter) WriteHeader(code int) {
	w.status = code
	w.decide()
	if !w.json {
		w.ResponseWriter.WriteHeader(code)
	}
}

func (w *rewriteWriter) Write(p []byte) (int, error) {
	if !w.decided {
		w.WriteHeader(http.StatusOK)
	}
	if w.json {
		return w.buf.Write(p) //nolint:wrapcheck // in-memory buffer, never fails
	}
	return w.ResponseWriter.Write(p) //nolint:wrapcheck // pass-through writer
}

// Flush keeps streaming (non-JSON) responses streaming.
func (w *rewriteWriter) Flush() {
	if w.json {
		return
	}
	if f, ok := w.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}

func (w *rewriteWriter) finish(from, to []byte) {
	if !w.decided || !w.json {
		return
	}
	body := bytes.ReplaceAll(w.buf.Bytes(), from, to)
	w.Header().Set("Content-Length", strconv.Itoa(len(body)))
	w.ResponseWriter.WriteHeader(w.status)
	_, _ = w.ResponseWriter.Write(body)
}
