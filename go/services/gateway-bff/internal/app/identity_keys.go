package app

import (
	"encoding/json"
	"strings"
)

// identityKeys name an organisation or a person inside a request body. Which organisation or person a seller call acts
// for is the session's (identity, by the bearer), never the client's, so these keys are removed, at any depth, from
// every body the BFF relays verbatim. Upstreams also ignore them (their DTOs have no such field for sellers; inventory's
// top-level ownerOrg is staff-only); dropping them here keeps that true if an upstream DTO ever gains one.
var identityKeys = map[string]bool{"ownerorg": true, "ownerorgid": true, "orgid": true, "sellerorgid": true,
	"organizationid": true, "ownerid": true, "userid": true, "customerid": true, "org": true, "providerid": true, "owner": true,
	"createdby": true, "sub": true, "subject": true}

// withoutIdentityKeys returns v (decoded JSON) without identity keys at any depth.
func withoutIdentityKeys(v any) any {
	switch x := v.(type) {
	case map[string]any:
		out := make(map[string]any, len(x))
		for k, e := range x {
			if !identityKeys[strings.ToLower(k)] {
				out[k] = withoutIdentityKeys(e)
			}
		}
		return out
	case []any:
		out := make([]any, len(x))
		for i, e := range x {
			out[i] = withoutIdentityKeys(e)
		}
		return out
	default:
		return v
	}
}

// rawWithoutIdentityKeys is withoutIdentityKeys for a raw JSON object; a body that is not an object is returned as is.
func rawWithoutIdentityKeys(body json.RawMessage) json.RawMessage {
	var m map[string]any
	if json.Unmarshal(body, &m) != nil {
		return body
	}
	clean, err := json.Marshal(withoutIdentityKeys(m))
	if err != nil {
		return body
	}
	return clean
}
