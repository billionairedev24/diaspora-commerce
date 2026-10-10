package dhl

import (
	"context"
	"fmt"
	"net/http"

	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/carrier/carrierhttp"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
)

// decode unmarshals a response body (malformed bodies are retryable carrier errors).
func decode(body []byte, dst any) error {
	if err := carrierhttp.Decode(domain.DHL, body, dst); err != nil {
		return fmt.Errorf("decode response: %w", err)
	}
	return nil
}

// rejected is a permanent carrier rejection found inside a 2xx response.
func rejected(code, msg string) error {
	return fmt.Errorf("rejected: %w", carrierhttp.Rejected(domain.DHL, code, msg))
}

func jsonRequest(ctx context.Context, method, target string, body any) (*http.Request, error) {
	req, err := carrierhttp.JSONRequest(ctx, method, target, body)
	if err != nil {
		return nil, fmt.Errorf("request: %w", err)
	}
	return req, nil
}
