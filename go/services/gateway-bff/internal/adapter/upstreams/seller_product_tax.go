package upstreams

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"mime/multipart"
	"net/http"
	"net/textproto"
	"net/url"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ---------------------------------------------------------------- catalog: seller tax readiness and evidence

type sellerTaxImpl struct{ c httpclient.Client }

var _ port.SellerTaxClient = (*sellerTaxImpl)(nil)

// NewSellerTax returns catalog's tax readiness and evidence client (contracts/openapi/catalog.yaml, tag Products).
func NewSellerTax(c httpclient.Client) port.SellerTaxClient { return &sellerTaxImpl{c: c} }

func (k *sellerTaxImpl) Readiness(ctx context.Context, bearer, productID string) (json.RawMessage, error) {
	res, err := call(ctx, k.c, httpclient.Request{Path: "/v1/products/" + url.PathEscape(productID) + "/tax-readiness", Bearer: bearer, UserBearer: true}, nil)
	if err != nil {
		return nil, err
	}
	return json.RawMessage(res.Body), nil
}

func (k *sellerTaxImpl) UploadEvidence(ctx context.Context, bearer, atin, factID string, f port.UploadFile) (json.RawMessage, error) {
	var buf bytes.Buffer
	w := multipart.NewWriter(&buf)
	if err := w.WriteField("factId", factID); err != nil {
		return nil, fmt.Errorf("tax evidence upload: %w", err)
	}
	h := textproto.MIMEHeader{}
	h.Set("Content-Disposition", fmt.Sprintf(`form-data; name="file"; filename=%q`, f.Name))
	h.Set("Content-Type", f.ContentType)
	part, err := w.CreatePart(h)
	if err != nil {
		return nil, fmt.Errorf("tax evidence upload: %w", err)
	}
	if _, err := part.Write(f.Data); err != nil {
		return nil, fmt.Errorf("tax evidence upload: %w", err)
	}
	if err := w.Close(); err != nil {
		return nil, fmt.Errorf("tax evidence upload: %w", err)
	}
	res, err := call(ctx, k.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/variants/" + url.PathEscape(atin) + "/tax-evidence",
		Bearer: bearer, UserBearer: true, Body: buf.Bytes(), ContentType: w.FormDataContentType(), Slow: true}, nil)
	if err != nil {
		return nil, err
	}
	return json.RawMessage(res.Body), nil
}

func (k *sellerTaxImpl) ListEvidence(ctx context.Context, bearer, atin string) (json.RawMessage, error) {
	res, err := call(ctx, k.c, httpclient.Request{Path: "/v1/variants/" + url.PathEscape(atin) + "/tax-evidence", Bearer: bearer, UserBearer: true}, nil)
	if err != nil {
		return nil, err
	}
	return json.RawMessage(res.Body), nil
}

func (k *sellerTaxImpl) EvidenceFile(ctx context.Context, bearer, id string) (domain.FileDownload, error) {
	return downloadFile(ctx, k.c, bearer, "/v1/tax-evidence/"+url.PathEscape(id)+"/file", nil, "tax-evidence")
}
