package upstreams

import (
	"bytes"
	"context"
	"fmt"
	"mime/multipart"
	"net/http"
	"net/textproto"
	"net/url"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ---------------------------------------------------------------- inventory: stock CSV import (S395)

type sellerStockImportImpl struct{ c httpclient.Client }

var _ port.SellerStockImportClient = (*sellerStockImportImpl)(nil)

// NewSellerStockImport returns inventory's stock import client (contracts/openapi/inventory.yaml, "Stock import").
func NewSellerStockImport(c httpclient.Client) port.SellerStockImportClient {
	return &sellerStockImportImpl{c: c}
}

func stockImportPath(id string) string { return "/v1/stock/imports/" + url.PathEscape(id) }

func (k *sellerStockImportImpl) Template(ctx context.Context, bearer, mode string) (domain.FileDownload, error) {
	return downloadFile(ctx, k.c, bearer, "/v1/stock/imports/template", url.Values{"mode": {mode}}, "afrotransact-stock-"+strings.ToLower(mode)+".csv")
}

func (k *sellerStockImportImpl) Upload(ctx context.Context, bearer, ownerOrg string, f port.UploadFile, mode string) (domain.StockImport, error) {
	var buf bytes.Buffer
	w := multipart.NewWriter(&buf)
	h := textproto.MIMEHeader{}
	h.Set("Content-Disposition", fmt.Sprintf(`form-data; name="file"; filename=%q`, f.Name))
	h.Set("Content-Type", f.ContentType)
	part, err := w.CreatePart(h)
	if err != nil {
		return domain.StockImport{}, fmt.Errorf("upload: %w", err)
	}
	if _, err := part.Write(f.Data); err != nil {
		return domain.StockImport{}, fmt.Errorf("upload: %w", err)
	}
	if err := w.Close(); err != nil {
		return domain.StockImport{}, fmt.Errorf("upload: %w", err)
	}
	q := url.Values{"mode": {mode}}
	if ownerOrg != "" {
		q.Set("ownerOrg", ownerOrg)
	}
	var out domain.StockImport
	_, err = call(ctx, k.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/stock/imports", Query: q, Bearer: bearer,
		Body: buf.Bytes(), ContentType: w.FormDataContentType(), Slow: true}, &out)
	return out, err
}

func (k *sellerStockImportImpl) List(ctx context.Context, bearer, ownerOrg string, page, size int) (domain.StockImportPage, error) {
	q := url.Values{"page": {strconv.Itoa(page)}, "size": {strconv.Itoa(size)}}
	if ownerOrg != "" {
		q.Set("ownerOrg", ownerOrg)
	}
	var out domain.StockImportPage
	_, err := call(ctx, k.c, httpclient.Request{Path: "/v1/stock/imports", Query: q, Bearer: bearer}, &out)
	return out, err
}

func (k *sellerStockImportImpl) Get(ctx context.Context, bearer, id string) (domain.StockImport, error) {
	var out domain.StockImport
	_, err := call(ctx, k.c, httpclient.Request{Path: stockImportPath(id), Bearer: bearer}, &out)
	return out, err
}

func (k *sellerStockImportImpl) Rows(ctx context.Context, bearer, id, status, outcome string, page, size int) (domain.StockImportRowPage, error) {
	q := url.Values{"page": {strconv.Itoa(page)}, "size": {strconv.Itoa(size)}}
	if status != "" {
		q.Set("status", status)
	}
	if outcome != "" {
		q.Set("outcome", outcome)
	}
	var out domain.StockImportRowPage
	_, err := call(ctx, k.c, httpclient.Request{Path: stockImportPath(id) + "/rows", Query: q, Bearer: bearer}, &out)
	return out, err
}

func (k *sellerStockImportImpl) Report(ctx context.Context, bearer, id string) (domain.FileDownload, error) {
	return downloadFile(ctx, k.c, bearer, stockImportPath(id)+"/report", nil, "stock-report.csv")
}

func (k *sellerStockImportImpl) Start(ctx context.Context, bearer, id string, validOnly bool) (domain.StockImport, error) {
	var out domain.StockImport
	_, err := call(ctx, k.c, httpclient.Request{Method: http.MethodPost, Path: stockImportPath(id) + "/start", Bearer: bearer,
		JSON: map[string]any{"validOnly": validOnly}, RetryUnsafe: true}, &out)
	return out, err
}
