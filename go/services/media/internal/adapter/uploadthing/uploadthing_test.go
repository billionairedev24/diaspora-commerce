package uploadthing_test

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/uploadthing"
)

const apiKey = "sk_test_1"

// fakeUploadThing plays the UploadThing API, ingest and CDN (never the real service). The CDN serves private files
// only with a valid signature, verified the way UploadThing does: HMAC-SHA256 over the URL up to &signature=.
type fakeUploadThing struct {
	t       *testing.T
	mu      sync.Mutex
	srv     *httptest.Server
	files   map[string][]byte
	acl     map[string]string
	pending map[string]string // key → acl requested in prepareUpload
	deleted []string
	now     time.Time
}

func newFake(t *testing.T) *fakeUploadThing {
	f := &fakeUploadThing{t: t, files: map[string][]byte{}, acl: map[string]string{}, pending: map[string]string{},
		now: time.Date(2026, 10, 10, 12, 0, 0, 0, time.UTC)}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /v7/prepareUpload", f.prepare)
	mux.HandleFunc("PUT /ingest/{key}", f.ingest)
	mux.HandleFunc("POST /v6/deleteFiles", f.delete)
	mux.HandleFunc("GET /f/{key}", f.serve)
	f.srv = httptest.NewServer(mux)
	t.Cleanup(f.srv.Close)
	return f
}

func (f *fakeUploadThing) prepare(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("X-Uploadthing-Api-Key") != apiKey {
		http.Error(w, "bad key", http.StatusUnauthorized)
		return
	}
	var body map[string]any
	_ = json.NewDecoder(r.Body).Decode(&body)
	f.mu.Lock()
	defer f.mu.Unlock()
	key := "utk" + strconv.Itoa(len(f.pending)+1)
	f.pending[key] = body["acl"].(string)
	_ = json.NewEncoder(w).Encode(map[string]string{"key": key, "url": f.srv.URL + "/ingest/" + key + "?sig=x"})
}

func (f *fakeUploadThing) ingest(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("Range") != "bytes=0-" {
		http.Error(w, "range", http.StatusBadRequest)
		return
	}
	file, _, err := r.FormFile("file")
	if err != nil {
		http.Error(w, "multipart field file expected", http.StatusBadRequest)
		return
	}
	data, _ := io.ReadAll(file)
	f.mu.Lock()
	defer f.mu.Unlock()
	key := r.PathValue("key")
	f.files[key], f.acl[key] = data, f.pending[key]
	_, _ = w.Write([]byte(`{"ufsUrl":"x"}`))
}

func (f *fakeUploadThing) delete(w http.ResponseWriter, r *http.Request) {
	var body struct {
		FileKeys []string `json:"fileKeys"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)
	f.mu.Lock()
	defer f.mu.Unlock()
	f.deleted = append(f.deleted, body.FileKeys...)
	_, _ = w.Write([]byte(`{"success":true}`))
}

func (f *fakeUploadThing) serve(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	key := r.PathValue("key")
	data, ok := f.files[key]
	if !ok {
		http.NotFound(w, r)
		return
	}
	if f.acl[key] != "public-read" {
		full := f.srv.URL + r.URL.RequestURI()
		unsigned, sig, found := strings.Cut(full, "&signature=hmac-sha256%3D")
		exp, _ := strconv.ParseInt(r.URL.Query().Get("expires"), 10, 64)
		h := hmac.New(sha256.New, []byte(apiKey))
		_, _ = h.Write([]byte(unsigned))
		if !found || sig != hex.EncodeToString(h.Sum(nil)) || exp < f.now.UnixMilli() {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
	}
	_, _ = w.Write(data)
}

func TestPrivateUploadModeratedPublishAndDelete(t *testing.T) {
	f := newFake(t)
	st := uploadthing.New(uploadthing.Options{APIKey: apiKey, AppID: "app1", BaseURL: f.srv.URL, CDNBaseURL: f.srv.URL,
		Now: func() time.Time { return f.now }}, f.srv.Client())
	ctx := context.Background()

	up, err := st.PrepareUpload(ctx, "quarantine/product-image/org/1.jpg", "dress.jpg", "image/jpeg", 4, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if up.Method != http.MethodPut || up.FormField != "file" || up.PublicURL != "" || up.Headers["Range"] != "bytes=0-" {
		t.Fatalf("browser uploads are private multipart PUTs: %+v", up)
	}
	if f.pending[up.Key] != "private" {
		t.Fatalf("browser upload acl %q", f.pending[up.Key])
	}
	// The browser uploads (multipart, as the UploadThing client does).
	browserUpload(t, f, up.URL, up.FormField, up.Headers, "orig")
	if f.acl[up.Key] != "private" {
		t.Fatal("the uploaded file must stay private")
	}
	if res, _ := get(f, f.srv.URL+"/f/"+up.Key); res != nil {
		_ = res.Body.Close()
		if res.StatusCode != http.StatusForbidden {
			t.Fatalf("unmoderated file must not be public: %d", res.StatusCode)
		}
	}

	// Reading a private file goes through a locally signed CDN URL.
	rc, _, _, err := st.Open(ctx, up.Key, 0)
	if err != nil {
		t.Fatalf("open private file with signed URL: %v", err)
	}
	got, _ := io.ReadAll(rc)
	_ = rc.Close()
	if string(got) != "orig" {
		t.Fatalf("read %q", got)
	}

	key, publicURL, err := st.Put(ctx, "product-image/org/1.jpg", "dress.jpg", "image/jpeg", []byte("clean"), true)
	if err != nil {
		t.Fatal(err)
	}
	if f.acl[key] != "public-read" || publicURL != f.srv.URL+"/f/"+key {
		t.Fatalf("published %s acl=%s url=%s", key, f.acl[key], publicURL)
	}
	res, err := get(f, publicURL)
	if err != nil {
		t.Fatal(err)
	}
	_ = res.Body.Close()
	if res.StatusCode != http.StatusOK {
		t.Fatalf("public file must be served without a signature: %d", res.StatusCode)
	}
	if err := st.Delete(ctx, up.Key); err != nil || len(f.deleted) != 1 {
		t.Fatalf("delete: %v %v", err, f.deleted)
	}
}

func get(f *fakeUploadThing, url string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(context.Background(), http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	return f.srv.Client().Do(req)
}

func browserUpload(t *testing.T, f *fakeUploadThing, url, field string, headers map[string]string, content string) {
	t.Helper()
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	fw, _ := mw.CreateFormFile(field, "dress.jpg")
	_, _ = fw.Write([]byte(content))
	_ = mw.Close()
	req, _ := http.NewRequestWithContext(context.Background(), http.MethodPut, url, &body)
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	res, err := f.srv.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	_ = res.Body.Close()
	if res.StatusCode != http.StatusOK {
		t.Fatalf("ingest status %d", res.StatusCode)
	}
}

func TestSignedURLsExpireAndAreCappedAtSevenDays(t *testing.T) {
	f := newFake(t)
	now := f.now
	st := uploadthing.New(uploadthing.Options{APIKey: apiKey, AppID: "app1", CDNBaseURL: f.srv.URL,
		Now: func() time.Time { return now }}, f.srv.Client())
	if st.MaxSignedURLTTL() != 7*24*time.Hour {
		t.Fatal("UploadThing signs for at most 7 days")
	}
	u, err := st.SignedGetURL(context.Background(), "k", 30*24*time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	want := now.Add(7 * 24 * time.Hour).UnixMilli()
	if !strings.Contains(u, "expires="+strconv.FormatInt(want, 10)+"&signature=hmac-sha256%3D") {
		t.Fatalf("signed url %s", u)
	}
	f.files["k"], f.acl["k"] = []byte("x"), "private"
	f.now = now.Add(8 * 24 * time.Hour)
	res, err := get(f, u)
	if err != nil {
		t.Fatal(err)
	}
	_ = res.Body.Close()
	if res.StatusCode != http.StatusForbidden {
		t.Fatalf("expired link must be refused, got %d", res.StatusCode)
	}
}

func TestParseToken(t *testing.T) {
	raw := base64.StdEncoding.EncodeToString([]byte(`{"apiKey":"sk_live_abc","appId":"app9","regions":["sea1"]}`))
	tok, err := uploadthing.ParseToken(raw)
	if err != nil || tok.APIKey != "sk_live_abc" || tok.AppID != "app9" {
		t.Fatalf("token %+v %v", tok, err)
	}
	if _, err := uploadthing.ParseToken(base64.StdEncoding.EncodeToString([]byte(`{"apiKey":"nope"}`))); err == nil {
		t.Fatal("invalid token must fail")
	}
}
