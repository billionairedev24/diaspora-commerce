package upstreams

import (
	"context"
	"net/http"
	"net/url"
	"strconv"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type creditImpl struct{ c httpclient.Client }

var _ port.CreditClient = (*creditImpl)(nil)

// NewCredit returns the payments client for the shopper's credit, gift cards (customer token).
func NewCredit(c httpclient.Client) port.CreditClient { return &creditImpl{c: c} }

func (p *creditImpl) Balance(ctx context.Context, bearer string) (port.UpCreditBalance, error) {
	var out port.UpCreditBalance
	_, err := call(ctx, p.c, httpclient.Request{Path: "/v1/credit/me", Bearer: bearer}, &out)
	return out, err
}

func (p *creditImpl) Entries(ctx context.Context, bearer string, before time.Time, limit int) ([]port.UpCreditEntry, error) {
	q := url.Values{"limit": {strconv.Itoa(limit)}}
	if !before.IsZero() {
		q.Set("before", before.UTC().Format(time.RFC3339Nano))
	}
	var out []port.UpCreditEntry
	_, err := call(ctx, p.c, httpclient.Request{Path: "/v1/credit/me/entries", Query: q, Bearer: bearer}, &out)
	return out, err
}

func (p *creditImpl) GiftCardConfig(ctx context.Context, bearer string) (port.UpGiftCardConfig, error) {
	var out port.UpGiftCardConfig
	_, err := call(ctx, p.c, httpclient.Request{Path: "/v1/gift-cards/config", Bearer: bearer}, &out)
	return out, err
}

func (p *creditImpl) MyGiftCards(ctx context.Context, bearer string) ([]port.UpGiftCard, error) {
	var out []port.UpGiftCard
	_, err := call(ctx, p.c, httpclient.Request{Path: "/v1/gift-cards/mine", Bearer: bearer}, &out)
	return out, err
}

func (p *creditImpl) StartGiftCard(ctx context.Context, bearer, key string, req port.UpGiftCardPurchaseRequest) (port.UpGiftCard, error) {
	var out port.UpGiftCard
	_, err := call(ctx, p.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/gift-cards/purchases", Bearer: bearer, JSON: req,
		Header: http.Header{"Idempotency-Key": {key}}, RetryUnsafe: true, Slow: true}, &out)
	return out, err
}

func (p *creditImpl) ConfirmGiftCard(ctx context.Context, bearer, id string) (port.UpGiftCard, error) {
	var out port.UpGiftCard
	_, err := call(ctx, p.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/gift-cards/purchases/" + url.PathEscape(id) + "/confirm",
		Bearer: bearer, RetryUnsafe: true, Slow: true}, &out)
	return out, err
}

func (p *creditImpl) Redeem(ctx context.Context, bearer, code, clientAddress string) (port.UpGiftCardRedemption, error) {
	var out port.UpGiftCardRedemption
	h := http.Header{}
	if clientAddress != "" {
		h.Set("X-Client-Address", clientAddress)
	}
	_, err := call(ctx, p.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/gift-cards/redeem", Bearer: bearer,
		JSON: map[string]string{"code": code}, Header: h}, &out)
	return out, err
}
