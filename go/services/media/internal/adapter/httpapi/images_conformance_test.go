package httpapi_test

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/getkin/kin-openapi/openapi3"
	"github.com/getkin/kin-openapi/openapi3filter"
	"github.com/getkin/kin-openapi/routers/gorillamux"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/port"
)

// imageMedia answers the S388 operations like the service does (the rules themselves are tested in domain and app).
type imageMedia struct {
	app.MediaService
	gotSlot  domain.ImageSlot
	gotHint  domain.ModerationHint
	override domain.ImageRulesOverride
}

func rejected() domain.Asset {
	checks := []domain.Check{domain.NewCheck(domain.CodeMainTooSmall, domain.VerdictReject, map[string]any{"w": 800, "h": 800, "px": 1000})}
	return domain.Asset{ID: "a1000000-0000-4000-8000-000000000009", OwnerOrg: "org_lagos", UploadedBy: "u1", Purpose: domain.PurposeProductImage,
		Provider: "mem", Key: "quarantine/x.jpg", FileName: "x.jpg", ContentType: "image/jpeg", Bytes: 1000, Width: 800, Height: 800,
		Status: domain.StatusRejected, Flags: domain.Messages(checks), CreatedAt: libraryTime, ImageSlot: domain.ImageSlotMain,
		Verdict: domain.VerdictReject, Checks: checks, Shadow: true,
		Moderation: &domain.Moderation{Verdict: domain.VerdictHeld, Reasons: []string{"x"}, AI: domain.AIChecked, DuplicateHeld: true}}
}

func (m *imageMedia) RequestImageUpload(_ context.Context, actor auth.Actor, _ domain.Purpose, _, _ string, _ int64, o domain.ImageOptions) (domain.Asset, port.Upload, error) {
	if actor.OrgID == "" {
		return domain.Asset{}, port.Upload{}, app.ErrForbidden
	}
	if o.Slot == "BANNER" {
		return domain.Asset{}, port.Upload{}, domain.ErrRejected
	}
	m.gotSlot, m.gotHint = o.Slot, o.Hint
	a := rejected()
	a.Status, a.Checks, a.Verdict, a.Flags = domain.StatusPending, nil, "", []string{}
	return a, port.Upload{Method: "PUT", URL: "https://store/x", Headers: map[string]string{}, Key: "quarantine/x.jpg", ExpiresAt: libraryTime}, nil
}

func (m *imageMedia) ImportImageFromURL(_ context.Context, actor auth.Actor, _ domain.Purpose, _, _, _ string, o domain.ImageOptions) (domain.Asset, error) {
	if actor.Type != "SERVICE" {
		return domain.Asset{}, app.ErrForbidden
	}
	m.gotSlot, m.gotHint = o.Slot, o.Hint
	return rejected(), nil
}

func (m *imageMedia) judge(actor auth.Actor, items []domain.VerifyItem) ([]domain.VerifyResult, error) {
	if actor.Type != "SERVICE" {
		return nil, app.ErrForbidden
	}
	if len(items) == 0 {
		return nil, domain.ErrRejected
	}
	out := []domain.VerifyResult{}
	for _, it := range items {
		c := domain.NewCheck(domain.CodeNotVerified, domain.VerdictReject, nil)
		out = append(out, domain.VerifyResult{URL: it.URL, Slot: it.Slot, Verdict: domain.VerdictReject, Checks: []domain.Check{c}})
	}
	out[0].Found, out[0].Width, out[0].Height, out[0].Measured = true, 1600, 1600, true
	out[0].Verdict, out[0].Checks = domain.VerdictPass, []domain.Check{}
	return out, nil
}

func (m *imageMedia) VerifyImages(_ context.Context, actor auth.Actor, items []domain.VerifyItem) ([]domain.VerifyResult, error) {
	return m.judge(actor, items)
}

func (m *imageMedia) RescoreImages(_ context.Context, actor auth.Actor, items []domain.VerifyItem) ([]domain.VerifyResult, error) {
	return m.judge(actor, items)
}

func (m *imageMedia) view() domain.ImageRulesView {
	return domain.ImageRulesView{Effective: m.override.Apply(domain.DefaultImageRules()), Override: domain.StoredImageRules{Override: m.override, UpdatedBy: "staff-9"}}
}

func (m *imageMedia) ImageRules(context.Context, auth.Actor) (domain.ImageRulesView, error) {
	return m.view(), nil
}

func (m *imageMedia) SetImageRules(_ context.Context, actor auth.Actor, o domain.ImageRulesOverride) (domain.ImageRulesView, error) {
	if actor.Type != "STAFF" {
		return domain.ImageRulesView{}, app.ErrForbidden
	}
	if p := o.Apply(domain.DefaultImageRules()).Problem(); p != "" {
		return domain.ImageRulesView{}, domain.ErrRejected
	}
	m.override = o
	return m.view(), nil
}

// TestImageChecksContractConformance validates every S388 operation, success and documented errors, against
// contracts/openapi/media.yaml.
func TestImageChecksContractConformance(t *testing.T) {
	doc, err := openapi3.NewLoader().LoadFromFile(contractPath)
	if err != nil {
		t.Fatal(err)
	}
	doc.Servers = openapi3.Servers{{URL: "http://media.internal"}}
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		t.Fatal(err)
	}
	media := &imageMedia{}
	actor := auth.Actor{Subject: "u1", Type: "SELLER_MEMBER", OrgID: "org_lagos"}
	mux := http.NewServeMux()
	httpapi.New(media).Register(mux, func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			next.ServeHTTP(w, r.WithContext(auth.WithActor(r.Context(), actor)))
		})
	})
	validated := map[string]int{}
	do := func(method, target, body string, want int) {
		t.Helper()
		var rd io.Reader
		if body != "" {
			rd = strings.NewReader(body)
		}
		req := httptest.NewRequestWithContext(context.Background(), method, "http://media.internal"+target, rd)
		if body != "" {
			req.Header.Set("Content-Type", "application/json")
		}
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		if rec.Code != want {
			t.Fatalf("%s %s = %d, want %d: %s", method, target, rec.Code, want, rec.Body.String())
		}
		route, params, err := router.FindRoute(req)
		if err != nil {
			t.Fatalf("%s %s is not in the contract: %v", method, target, err)
		}
		in := &openapi3filter.ResponseValidationInput{
			RequestValidationInput: &openapi3filter.RequestValidationInput{Request: req, PathParams: params, Route: route,
				Options: &openapi3filter.Options{AuthenticationFunc: openapi3filter.NoopAuthenticationFunc}},
			Status: rec.Code, Header: rec.Header(), Body: io.NopCloser(bytes.NewReader(rec.Body.Bytes())),
			Options: &openapi3filter.Options{IncludeResponseStatus: true, MultiError: true},
		}
		if err := openapi3filter.ValidateResponse(context.Background(), in); err != nil {
			t.Errorf("%s %s → %d does not match the contract: %v\nbody: %s", method, target, rec.Code, err, rec.Body.String())
			return
		}
		validated[route.Operation.OperationID]++
	}

	do("POST", "/v1/uploads", `{"purpose":"product-image","fileName":"a.jpg","contentType":"image/jpeg","size":1000,"slot":"MAIN","title":"Ankara dress","brand":"Adire","productTypeCode":"APPAREL_DRESS"}`, 201)
	if media.gotSlot != domain.ImageSlotMain || media.gotHint.Title != "Ankara dress" || media.gotHint.Brand != "Adire" || media.gotHint.ProductTypeCode != "APPAREL_DRESS" {
		t.Fatalf("the slot and hint reach the service: %v %+v", media.gotSlot, media.gotHint)
	}
	do("POST", "/v1/uploads", `{"purpose":"product-image","fileName":"a.jpg","contentType":"image/jpeg","size":1000,"slot":"BANNER"}`, 422)
	actor = auth.Actor{Subject: domain.ClientCatalogService, Type: "SERVICE", Permissions: []string{domain.PermissionMediaImport}}
	do("POST", "/v1/assets/import", `{"purpose":"product-image","url":"https://cdn.example.com/a.jpg","ownerOrg":"org_lagos","slot":"OTHER"}`, 201)
	if media.gotSlot != domain.ImageSlotOther {
		t.Fatalf("import slot %v", media.gotSlot)
	}
	do("POST", "/v1/assets/verify", `{"images":[{"url":"https://cdn.example.com/a.jpg","slot":"MAIN"},{"url":"https://elsewhere.example.com/b.jpg","slot":"MAIN","productTypeCode":"APPAREL_DRESS"}]}`, 200)
	do("POST", "/v1/assets/verify", `{"images":[]}`, 422)
	do("POST", "/v1/assets/verify", `not json`, 400)
	do("POST", "/v1/assets/rescore", `{"images":[{"url":"https://cdn.example.com/a.jpg","slot":"MAIN"}]}`, 200)
	do("GET", "/v1/image-rules", "", 200)
	do("PUT", "/v1/image-rules", `{"warnOnly":true}`, 403)
	actor = auth.Actor{Subject: "staff-9", Type: "STAFF", Permissions: []string{"catalog:moderate"}}
	do("PUT", "/v1/image-rules", `{"warnOnly":true,"fillReject":0.7}`, 200)
	do("PUT", "/v1/image-rules", `{"fillReject":0.9,"fillWarn":0.8}`, 422)
	do("PUT", "/v1/image-rules", `{"unknownThreshold":1}`, 400)
	actor = auth.Actor{Subject: "u1", Type: "SELLER_MEMBER", OrgID: "org_lagos"}
	do("POST", "/v1/assets/verify", `{"images":[{"url":"https://cdn.example.com/a.jpg","slot":"MAIN"}]}`, 403)
	do("POST", "/v1/assets/import", `{"purpose":"product-image","url":"https://cdn.example.com/a.jpg","ownerOrg":"org_lagos"}`, 403)

	for _, op := range []string{"verifyImages", "rescoreImages", "getImageRules", "setImageRules", "requestUpload", "importAsset"} {
		if validated[op] == 0 {
			t.Errorf("operation %s was not validated", op)
		}
	}
}
