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
