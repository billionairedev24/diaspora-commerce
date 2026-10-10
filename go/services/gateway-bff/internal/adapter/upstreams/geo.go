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

type geoImpl struct {
	c      httpclient.Client
	cache  port.Cache
	zipTTL time.Duration
}

var _ port.GeoClient = (*geoImpl)(nil)

// NewGeo returns the geo-service client. ZIP lookups (public reference data) are cached for zipTTL.
func NewGeo(c httpclient.Client, cache port.Cache, zipTTL time.Duration) port.GeoClient {
	return &geoImpl{c: c, cache: cache, zipTTL: zipTTL}
}

func (g *geoImpl) Autocomplete(ctx context.Context, q, session, country string) ([]port.GeoSuggestion, error) {
	v := url.Values{"q": {q}}
	if session != "" {
		v.Set("session", session)
	}
	if country != "" {
		v.Set("country", country)
	}
	var out struct {
		Suggestions []port.GeoSuggestion `json:"suggestions"`
	}
	_, err := call(ctx, g.c, httpclient.Request{Path: "/v1/addresses/autocomplete", Query: v}, &out)
	return out.Suggestions, err
}

func (g *geoImpl) Resolve(ctx context.Context, id, session string) (port.GeoAddress, error) {
	v := url.Values{}
	if session != "" {
		v.Set("session", session)
	}
	var out port.GeoAddress
	_, err := call(ctx, g.c, httpclient.Request{Path: "/v1/addresses/resolve/" + url.PathEscape(id), Query: v}, &out)
	return out, err
}

func (g *geoImpl) Validate(ctx context.Context, a port.GeoAddress) (port.GeoValidation, error) {
	var out port.GeoValidation
	_, err := call(ctx, g.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/addresses/validate", JSON: a}, &out)
	return out, err
}

func (g *geoImpl) Zip(ctx context.Context, zip string) (port.GeoAddress, error) {
	return cached(g.cache, "geo:zip:"+zip, func() (port.GeoAddress, http.Header, error) {
		var out port.GeoAddress
		res, err := call(ctx, g.c, httpclient.Request{Path: "/v1/zip/" + url.PathEscape(zip)}, &out)
		if err != nil {
			return out, nil, err
		}
		return out, res.Header, nil
	}, func(http.Header) time.Duration { return g.zipTTL })
}

// Reverse is geo's GET /v1/reverse?lat=&lng= (Google Geocoding, then Nominatim); 404 when no address is there.
func (g *geoImpl) Reverse(ctx context.Context, lat, lon float64) (port.GeoAddress, error) {
	v := url.Values{"lat": {strconv.FormatFloat(lat, 'f', 6, 64)}, "lng": {strconv.FormatFloat(lon, 'f', 6, 64)}}
	var out port.GeoAddress
	_, err := call(ctx, g.c, httpclient.Request{Path: "/v1/reverse", Query: v}, &out)
	return out, err
}
