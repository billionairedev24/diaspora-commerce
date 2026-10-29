package upstreams

import (
	"context"
	"net/http"
	"net/url"
	"strconv"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// proofLinkTTL is how long a link to a proof photo stays valid (media caps private photos at 5 minutes): long enough to look at it, short enough that a
// copied link is useless tomorrow.
const proofLinkTTL = 5 * 60

// maxProofAssets bounds the lookups one page makes (a photo and a signature per delivered shipment).
const maxProofAssets = 12

type proofLinksImpl struct {
	c      httpclient.Client
	tokens port.ServiceTokens
}

var _ port.ProofLinks = (*proofLinksImpl)(nil)

// NewProofLinks returns the ProofLinks reading media-service GET /v1/assets/{id} with scope media:read.
func NewProofLinks(c httpclient.Client, tokens port.ServiceTokens) port.ProofLinks {
	return &proofLinksImpl{c: c, tokens: tokens}
}

func (p *proofLinksImpl) URLs(ctx context.Context, assetIDs ...string) map[string]string {
	out := map[string]string{}
	if len(assetIDs) == 0 {
		return out
	}
	token, err := p.tokens.Token(ctx, "media:read")
	if err != nil {
		return out
	}
	for _, id := range assetIDs {
		if id == "" || len(out) >= maxProofAssets {
			continue
		}
		if _, seen := out[id]; seen {
			continue
		}
		var a struct {
			URL string `json:"url"`
		}
		if _, err := call(ctx, p.c, httpclient.Request{Method: http.MethodGet, Path: "/v1/assets/" + url.PathEscape(id), Bearer: token,
			Query: url.Values{"urlTtlSeconds": {strconv.Itoa(proofLinkTTL)}}}, &a); err == nil && a.URL != "" {
			out[id] = a.URL
		}
	}
	return out
}
