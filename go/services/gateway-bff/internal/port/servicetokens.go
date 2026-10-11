package port

import "context"

// ServiceTokens issues the BFF's own client-credentials access tokens (gateway-bff) for calls made on behalf of
// anonymous shoppers (e.g. the careers résumé upload). scope "" asks for the client's default scopes.
type ServiceTokens interface {
	Token(ctx context.Context, scope string) (string, error)
}
