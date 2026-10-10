package uploadthing_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/uploadthing"
)

func TestPrepareUploadSendsKeyAndMetadata(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v7/prepareUpload" || r.Header.Get("X-Uploadthing-Api-Key") != "sk_test_1" {
			http.Error(w, "bad", http.StatusBadRequest)
			return
		}
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		if body["fileType"] != "image/jpeg" || body["customId"] != "product-image/org/1.jpg" {
			http.Error(w, "bad body", http.StatusBadRequest)
			return
		}
		_, _ = w.Write([]byte(`{"key":"ut_key_1","url":"https://sea1.ingest.uploadthing.com/ut_key_1?sig=x"}`))
	}))
	defer srv.Close()
	st := uploadthing.New(uploadthing.Options{APIKey: "sk_test_1", AppID: "app1", BaseURL: srv.URL}, srv.Client())
	up, err := st.PrepareUpload(context.Background(), "product-image/org/1.jpg", "dress.jpg", "image/jpeg", 1000, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if up.Key != "ut_key_1" || up.PublicURL != "https://app1.ufs.sh/f/ut_key_1" || up.Method != http.MethodPut {
		t.Fatalf("unexpected upload %+v", up)
	}
}
