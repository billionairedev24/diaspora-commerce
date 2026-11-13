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
	"strconv"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ---------------------------------------------------------------- catalog: seller size charts

type sizeChartsImpl struct{ c httpclient.Client }

var _ port.SizeChartClient = (*sizeChartsImpl)(nil)

// NewSizeCharts returns catalog's size chart client (contracts/openapi/catalog.yaml, tag Size charts).
func NewSizeCharts(c httpclient.Client) port.SizeChartClient { return &sizeChartsImpl{c: c} }

const sizeChartsPath = "/v1/size-charts"

func (k *sizeChartsImpl) Call(ctx context.Context, bearer, method, subpath string, query url.Values, body json.RawMessage) (json.RawMessage, error) {
	req := httpclient.Request{Method: method, Path: sizeChartsPath + subpath, Query: query, Bearer: bearer, UserBearer: true}
	if len(body) > 0 {
		req.JSON = body
	}
	res, err := call(ctx, k.c, req, nil)
	if err != nil {
		return nil, err
	}
	return json.RawMessage(res.Body), nil
}

func (k *sizeChartsImpl) ImportTemplate(ctx context.Context, bearer, productType, productTypeID, format string) (domain.FileDownload, error) {
	q := url.Values{"format": {format}}
	if productType != "" {
		q.Set("productType", productType)
	}
	if productTypeID != "" {
		q.Set("productTypeId", productTypeID)
	}
	return downloadFile(ctx, k.c, bearer, sizeChartsPath+"/import-template", q, "afrotransact-size-charts."+format)
}

func (k *sizeChartsImpl) Import(ctx context.Context, bearer string, f port.UploadFile, productType, productTypeID string, dryRun bool) (json.RawMessage, error) {
	var buf bytes.Buffer
	w := multipart.NewWriter(&buf)
	h := textproto.MIMEHeader{}
	h.Set("Content-Disposition", fmt.Sprintf(`form-data; name="file"; filename=%q`, f.Name))
	h.Set("Content-Type", f.ContentType)
	part, err := w.CreatePart(h)
	if err != nil {
		return nil, fmt.Errorf("size chart upload: %w", err)
	}
	if _, err := part.Write(f.Data); err != nil {
		return nil, fmt.Errorf("size chart upload: %w", err)
	}
	if err := w.Close(); err != nil {
		return nil, fmt.Errorf("size chart upload: %w", err)
	}
	q := url.Values{"dryRun": {strconv.FormatBool(dryRun)}}
	if productType != "" {
		q.Set("productType", productType)
	}
	if productTypeID != "" {
		q.Set("productTypeId", productTypeID)
	}
	res, err := call(ctx, k.c, httpclient.Request{Method: http.MethodPost, Path: sizeChartsPath + "/import", Query: q, Bearer: bearer, UserBearer: true,
		Body: buf.Bytes(), ContentType: w.FormDataContentType(), Slow: true}, nil)
	if err != nil {
		return nil, err
	}
	return json.RawMessage(res.Body), nil
}
