package upstreams

import (
	"context"
	"net/http"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type assistantModelImpl struct{ c httpclient.Client }

var _ port.AssistantModelClient = (*assistantModelImpl)(nil)

// NewAssistantModel returns the AssistantModelClient (ai-gateway POST /v1/ai/seller-assistant/complete).
func NewAssistantModel(c httpclient.Client) port.AssistantModelClient {
	return &assistantModelImpl{c: c}
}

func (a *assistantModelImpl) Complete(ctx context.Context, bearer string, messages []port.AssistantMessage, tools []port.AssistantTool, maxTokens int) (port.AssistantCompletion, error) {
	body := map[string]any{"messages": messages, "tools": tools}
	if maxTokens > 0 {
		body["maxTokens"] = maxTokens
	}
	var out port.AssistantCompletion
	_, err := call(ctx, a.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/ai/seller-assistant/complete", Bearer: bearer, JSON: body}, &out)
	return out, err
}
