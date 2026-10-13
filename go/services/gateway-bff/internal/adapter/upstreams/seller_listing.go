package upstreams

import (
	"bytes"
	"context"
	"fmt"
	"mime"
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

// ---------------------------------------------------------------- catalog: one-step form, drafts, bulk upload (S307/S308)

type sellerListingImpl struct{ c httpclient.Client }

var _ port.SellerListingClient = (*sellerListingImpl)(nil)

// NewSellerListing returns catalog's product edit, draft and bulk upload client (contracts/openapi/catalog.yaml).
func NewSellerListing(c httpclient.Client) port.SellerListingClient { return &sellerListingImpl{c: c} }

func productPath(id string) string { return "/v1/products/" + url.PathEscape(id) }

func (k *sellerListingImpl) Product(ctx context.Context, bearer, productID string) (port.CatalogOwnProduct, error) {
	var p port.CatalogOwnProduct
	_, err := call(ctx, k.c, httpclient.Request{Path: productPath(productID), Bearer: bearer}, &p)
	return p, err
}

// productBody is catalog's CreateProductRequest / UpdateProductRequest for a form submission.
func productBody(d domain.ProductDraft, shipFrom *port.CatalogShipFrom) map[string]any {
	variants := make([]map[string]any, 0, len(d.Variants))
	for _, v := range d.Variants {
		vb := map[string]any{"options": v.Options, "sellerSku": v.SellerSKU, "gtin": v.GTIN, "gtinExempt": v.GTINExempt,
			"priceMinor": v.PriceMinor, "quantity": v.Quantity}
		if v.Options == nil {
			vb["options"] = map[string]string{}
		}
		if v.CompareAtMinor != nil {
			vb["compareAtMinor"] = *v.CompareAtMinor
		}
		if len(v.Images) > 0 {
			vb["images"] = v.Images
		}
		variants = append(variants, vb)
	}
	body := map[string]any{"title": strings.TrimSpace(d.Title), "attributes": d.Attributes, "images": d.Images,
		"variationTheme": strings.TrimSpace(d.VariationTheme), "variants": variants}
	if d.Attributes == nil {
		body["attributes"] = map[string]string{}
	}
	if d.PreorderEligible != nil {
		body["preorderEligible"] = *d.PreorderEligible
	}
	if d.DraftID != "" {
		body["draftId"] = d.DraftID
	}
	if l := d.Listing; l != nil {
		listing := map[string]any{"description": l.Description, "keywords": l.Keywords}
		if l.Condition != "" {
			listing["condition"] = l.Condition
		}
		if l.Fulfilment != "" {
			listing["fulfilment"] = l.Fulfilment
		}
		if l.Packaging != nil {
			listing["packaging"] = l.Packaging
		}
		if l.HandlingDays > 0 {
			listing["handlingDays"] = l.HandlingDays
		}
		if shipFrom != nil {
			listing["shipFrom"] = shipFrom
		}
		body["listing"] = listing
	}
	return body
}

func (k *sellerListingImpl) CreateFull(ctx context.Context, bearer string, d domain.ProductDraft, shipFrom *port.CatalogShipFrom) (port.CatalogOwnProduct, error) {
	body := productBody(d, shipFrom)
	body["currency"] = domain.Currency
	if d.CategoryID != "" {
		body["categoryId"] = d.CategoryID
	} else {
		body["categoryRequestId"] = d.CategoryRequestID
	}
	var p port.CatalogOwnProduct
	_, err := call(ctx, k.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/products", Bearer: bearer, JSON: body}, &p)
	return p, err
}

func (k *sellerListingImpl) Update(ctx context.Context, bearer, productID string, d domain.ProductDraft, shipFrom *port.CatalogShipFrom) (port.CatalogOwnProduct, error) {
	body := productBody(d, shipFrom)
	if d.CategoryID != "" {
		body["categoryId"] = d.CategoryID
	}
	if d.Version != nil {
		body["version"] = *d.Version
	}
	var p port.CatalogOwnProduct
	_, err := call(ctx, k.c, httpclient.Request{Method: http.MethodPut, Path: productPath(productID), Bearer: bearer, JSON: body}, &p)
	return p, err
}

func (k *sellerListingImpl) Drafts(ctx context.Context, bearer string) ([]port.CatalogDraft, error) {
	res, err := call(ctx, k.c, httpclient.Request{Path: "/v1/product-drafts", Bearer: bearer}, nil)
	if err != nil {
		return nil, err
	}
	items, _, err := itemsOf[port.CatalogDraft](res.Body)
	return items, err
}

func draftPath(id string) string { return "/v1/product-drafts/" + url.PathEscape(id) }

func (k *sellerListingImpl) Draft(ctx context.Context, bearer, id string) (port.CatalogDraft, error) {
	var d port.CatalogDraft
	_, err := call(ctx, k.c, httpclient.Request{Path: draftPath(id), Bearer: bearer}, &d)
	return d, err
}

func (k *sellerListingImpl) CreateDraft(ctx context.Context, bearer string, in domain.SaveProductDraftRequest) (port.CatalogDraft, error) {
	body := map[string]any{"title": in.Title, "payload": in.Payload}
	if in.ProductID != "" {
		body["productId"] = in.ProductID
	}
	if in.CategoryPath != "" {
		body["categoryPath"] = in.CategoryPath
	}
	var d port.CatalogDraft
	_, err := call(ctx, k.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/product-drafts", Bearer: bearer, JSON: body}, &d)
	return d, err
}

func (k *sellerListingImpl) SaveDraft(ctx context.Context, bearer, id string, in domain.SaveProductDraftRequest) (port.CatalogDraft, error) {
	body := map[string]any{"title": in.Title, "payload": in.Payload}
	if in.Version != nil {
		body["version"] = *in.Version
	}
	if in.CategoryPath != "" {
		body["categoryPath"] = in.CategoryPath
	}
	var d port.CatalogDraft
	_, err := call(ctx, k.c, httpclient.Request{Method: http.MethodPut, Path: draftPath(id), Bearer: bearer, JSON: body}, &d)
	return d, err
}

func (k *sellerListingImpl) DeleteDraft(ctx context.Context, bearer, id string) error {
	_, err := call(ctx, k.c, httpclient.Request{Method: http.MethodDelete, Path: draftPath(id), Bearer: bearer}, nil)
	return err
}

// download reads a generated file (template, report) with its name from Content-Disposition.
func (k *sellerListingImpl) download(ctx context.Context, bearer, path string, q url.Values, fallback string) (domain.FileDownload, error) {
	res, err := call(ctx, k.c, httpclient.Request{Path: path, Query: q, Bearer: bearer,
		Header: http.Header{"Accept": {"*/*"}}}, nil)
	if err != nil {
		return domain.FileDownload{}, err
	}
	name := fallback
	if _, params, perr := mime.ParseMediaType(res.Header.Get("Content-Disposition")); perr == nil && params["filename"] != "" {
		name = params["filename"]
	}
	ct := res.Header.Get("Content-Type")
	if ct == "" {
		ct = "application/octet-stream"
	}
	return domain.FileDownload{FileName: name, ContentType: ct, Content: res.Body}, nil
}

func (k *sellerListingImpl) ImportTemplate(ctx context.Context, bearer, productType, categoryID, format string) (domain.FileDownload, error) {
	q := url.Values{"format": {format}}
	if productType != "" {
		q.Set("productType", productType)
	}
	if categoryID != "" {
		q.Set("categoryId", categoryID)
	}
	return k.download(ctx, bearer, "/v1/imports/template", q, "afrotransact-products."+format)
}

func (k *sellerListingImpl) Upload(ctx context.Context, bearer string, f port.UploadFile, mode, productType, uploadedByName string) (port.CatalogImport, error) {
	var buf bytes.Buffer
	w := multipart.NewWriter(&buf)
	h := textproto.MIMEHeader{}
	h.Set("Content-Disposition", fmt.Sprintf(`form-data; name="file"; filename=%q`, f.Name))
	h.Set("Content-Type", f.ContentType)
	part, err := w.CreatePart(h)
	if err != nil {
		return port.CatalogImport{}, fmt.Errorf("upload: %w", err)
	}
	if _, err := part.Write(f.Data); err != nil {
		return port.CatalogImport{}, fmt.Errorf("upload: %w", err)
	}
	if err := w.Close(); err != nil {
		return port.CatalogImport{}, fmt.Errorf("upload: %w", err)
	}
	q := url.Values{"mode": {mode}}
	if productType != "" {
		q.Set("productType", productType)
	}
	if uploadedByName != "" {
		q.Set("uploadedByName", uploadedByName)
	}
	var out port.CatalogImport
	_, err = call(ctx, k.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/imports", Query: q, Bearer: bearer,
		Body: buf.Bytes(), ContentType: w.FormDataContentType(), Slow: true}, &out)
	return out, err
}

func importPath(id string) string { return "/v1/imports/" + url.PathEscape(id) }

func (k *sellerListingImpl) Imports(ctx context.Context, bearer string, page, size int) (port.CatalogImportPage, error) {
	var out port.CatalogImportPage
	_, err := call(ctx, k.c, httpclient.Request{Path: "/v1/imports", Bearer: bearer,
		Query: url.Values{"page": {strconv.Itoa(page)}, "size": {strconv.Itoa(size)}}}, &out)
	return out, err
}

func (k *sellerListingImpl) Import(ctx context.Context, bearer, id string) (port.CatalogImport, error) {
	var out port.CatalogImport
	_, err := call(ctx, k.c, httpclient.Request{Path: importPath(id), Bearer: bearer}, &out)
	return out, err
}

func (k *sellerListingImpl) ImportRows(ctx context.Context, bearer, id, status, outcome string, page, size int) (port.CatalogImportRowPage, error) {
	q := url.Values{"page": {strconv.Itoa(page)}, "size": {strconv.Itoa(size)}}
	if status != "" {
		q.Set("status", status)
	}
	if outcome != "" {
		q.Set("outcome", outcome)
	}
	var out port.CatalogImportRowPage
	_, err := call(ctx, k.c, httpclient.Request{Path: importPath(id) + "/rows", Query: q, Bearer: bearer}, &out)
	return out, err
}

func (k *sellerListingImpl) ImageUsage(ctx context.Context, bearer string, urls, skus []string) (port.CatalogImageUsage, error) {
	var out port.CatalogImageUsage
	_, err := call(ctx, k.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/image-library/usage", Bearer: bearer,
		JSON: map[string]any{"urls": urls, "skus": skus}}, &out)
	return out, err
}

func (k *sellerListingImpl) AttachLibraryImages(ctx context.Context, bearer string, skus []string) (domain.AttachLibraryImagesResult, error) {
	var out domain.AttachLibraryImagesResult
	_, err := call(ctx, k.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/image-library/attach", Bearer: bearer,
		JSON: map[string]any{"skus": skus}}, &out)
	if out.Skipped == nil {
		out.Skipped = []domain.AttachLibrarySkipped{}
	}
	return out, err
}

func (k *sellerListingImpl) Brands(ctx context.Context, bearer, q string) (domain.BrandList, error) {
	v := url.Values{"limit": {"20"}}
	if q != "" {
		v.Set("q", q)
	}
	var out domain.BrandList
	_, err := call(ctx, k.c, httpclient.Request{Path: "/v1/brands", Query: v, Bearer: bearer}, &out)
	if out.Brands == nil {
		out.Brands = []domain.Brand{}
	}
	return out, err
}

func (k *sellerListingImpl) ImportReport(ctx context.Context, bearer, id, format string) (domain.FileDownload, error) {
	return k.download(ctx, bearer, importPath(id)+"/report", url.Values{"format": {format}}, "upload-report."+format)
}

func (k *sellerListingImpl) StartImport(ctx context.Context, bearer, id string, validOnly bool) (port.CatalogImport, error) {
	var out port.CatalogImport
	_, err := call(ctx, k.c, httpclient.Request{Method: http.MethodPost, Path: importPath(id) + "/start", Bearer: bearer,
		JSON: map[string]any{"validOnly": validOnly}, RetryUnsafe: true}, &out)
	return out, err
}

// ---------------------------------------------------------------- ai-gateway listing writer

type listingWriterImpl struct{ c httpclient.Client }

var _ port.ListingWriterClient = (*listingWriterImpl)(nil)

// NewListingWriter returns ai-gateway's listing writer client (POST /v1/ai/listing-writer).
func NewListingWriter(c httpclient.Client) port.ListingWriterClient { return &listingWriterImpl{c: c} }

func (l *listingWriterImpl) Write(ctx context.Context, bearer string, in domain.ListingWriterRequest) (domain.ListingWriterDraft, error) {
	var out struct {
		Title       string   `json:"title"`
		Description string   `json:"description"`
		Bullets     []string `json:"bullets"`
	}
	body := map[string]any{"productType": in.ProductType, "title": in.Title, "attributes": in.Attributes, "language": "en",
		"tone": in.Tone}
	if _, err := call(ctx, l.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/ai/listing-writer", Bearer: bearer,
		JSON: body, Slow: true}, &out); err != nil {
		return domain.ListingWriterDraft{}, err
	}
	if out.Bullets == nil {
		out.Bullets = []string{}
	}
	return domain.ListingWriterDraft{Title: out.Title, Description: out.Description, Bullets: out.Bullets}, nil
}
