package upstreams

import (
	"context"
	"net/url"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type taxStatementsImpl struct{ c httpclient.Client }

var _ port.TaxStatementClient = (*taxStatementsImpl)(nil)

// NewTaxStatements returns the client of tax-returns' seller tax statements.
func NewTaxStatements(c httpclient.Client) port.TaxStatementClient { return &taxStatementsImpl{c: c} }

func (t *taxStatementsImpl) Statement(ctx context.Context, bearer, orgID, month string) (port.TaxStatementResponse, error) {
	var out port.TaxStatementResponse
	_, err := call(ctx, t.c, httpclient.Request{Path: "/v1/tax/sellers/" + url.PathEscape(orgID) + "/statement", Query: url.Values{"month": {month}}, Bearer: bearer}, &out)
	return out, err
}

func (t *taxStatementsImpl) StatementCSV(ctx context.Context, bearer, orgID, month string) ([]byte, error) {
	res, err := call(ctx, t.c, httpclient.Request{Path: "/v1/tax/sellers/" + url.PathEscape(orgID) + "/statement/export", Query: url.Values{"month": {month}}, Bearer: bearer}, nil)
	if err != nil {
		return nil, err
	}
	return res.Body, nil
}
