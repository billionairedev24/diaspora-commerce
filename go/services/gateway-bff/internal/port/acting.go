package port

import "context"

type shopperKey struct{}

// WithShopper marks ctx as a storefront or customer-app request: upstream calls that relay the person's token send
// X-Acting-As: CUSTOMER (go/pkg/auth ActingAs), so a person who also belongs to a seller or provider organisation
// (identity's token then carries pt SELLER_MEMBER / PROVIDER_MEMBER and the org) shops as themselves. Seller Center,
// Provider Hub and staff console requests are never marked: the organisation context applies only there.
func WithShopper(ctx context.Context) context.Context {
	return context.WithValue(ctx, shopperKey{}, true)
}

// IsShopper reports whether ctx is a storefront or customer-app request (WithShopper).
func IsShopper(ctx context.Context) bool {
	v, _ := ctx.Value(shopperKey{}).(bool)
	return v
}

type serviceBearerKey struct{}

// WithServiceBearer marks ctx as a call whose bearer is the gateway's own client-credentials token (a signed-out visitor
// on a guest path), not the person's token: methods shared by both paths then never send X-Acting-As.
func WithServiceBearer(ctx context.Context) context.Context {
	return context.WithValue(ctx, serviceBearerKey{}, true)
}

// IsServiceBearer reports whether ctx carries WithServiceBearer.
func IsServiceBearer(ctx context.Context) bool {
	v, _ := ctx.Value(serviceBearerKey{}).(bool)
	return v
}

// WithoutShopper is ctx without the shopper mark: a storefront route reads the person's own back-office state (their
// seller or provider application) with the token as identity issued it, never acting as a shopper.
func WithoutShopper(ctx context.Context) context.Context {
	return context.WithValue(ctx, shopperKey{}, false)
}
