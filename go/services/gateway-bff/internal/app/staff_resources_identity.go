package app

import (
	"context"
	"net/http"
	"net/url"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Identity & access (Developer tools, S291): OAuth clients, sessions and tokens, signing keys and the authentication
// settings of the identity service. Everything needs identity:admin (module "identity"); identity enforces the permission
// again, requires a reason for every change and writes the access change log. Secrets are shown once (staffCtx.showOnce).

func identityAdminResources() []*staffResource {
	return []*staffResource{oauthClientsResource(), identitySessionsResource(), signingKeysResource(), authSettingsResource()}
}

const identityAdmin = domain.PermIdentityAdmin

var grantTypeOptions = domain.StaffOptions("authorization_code", "refresh_token", "client_credentials")

// lines splits a textarea into its non-empty lines.
func lines(v jobj, key string) []string {
	out := []string{}
	for _, l := range strings.Split(vs(v, key), "\n") {
		if l = strings.TrimSpace(l); l != "" {
			out = append(out, l)
		}
	}
	return out
}

func joinLines(items []string) string { return strings.Join(items, "\n") }

func yesOrNo(b bool) string {
	if b {
		return "Yes"
	}
	return "No"
}

func minutes(o jobj, path string) string {
	n := num(o, path)
	if n == 0 {
		return ""
	}
	switch {
	case int64(n)%1440 == 0:
		return displayValue(n/1440) + " days"
	case int64(n)%60 == 0:
		return displayValue(n/60) + " hours"
	default:
		return displayValue(n) + " minutes"
	}
}

func reasonField() domain.FormField {
	return withPattern(withMax(ff("reason", "Reason (10 to 500 characters, kept in the access log)", "textarea", true), 500), `^[\s\S]{10,500}$`, "Give a reason of 10 to 500 characters")
}

// ---------------------------------------------------------------- OAuth clients

func oauthClientsResource() *staffResource {
	status := func(o jobj) string {
		if boolean(o, "enabled") {
			return "ENABLED"
		}
		return "DISABLED"
	}
	load := func(ctx context.Context, sc staffCtx, _ staffQuery) ([]jobj, error) {
		var raw any
		if err := sc.get(ctx, sc.up.Identity, "/v1/admin/identity/clients", nil, &raw); err != nil {
			return nil, err
		}
		return jpage(raw).items, nil
	}
	clientPath := func(o jobj, suffix string) string {
		return "/v1/admin/identity/clients/" + url.PathEscape(jstr(o, "clientId")) + suffix
	}
	// body builds the identity request from a create or edit form.
	body := func(v jobj) jobj {
		b := jobj{"name": vs(v, "name"), "grantTypes": vlist(v, "grantTypes"), "scopes": lines(v, "scopes"),
			"redirectUris": lines(v, "redirectUris"), "postLogoutRedirectUris": lines(v, "postLogoutRedirectUris"),
			"requireConsent": vb(v, "requireConsent"), "publicRefreshTokens": vb(v, "publicRefreshTokens"),
			"accessTokenTtlMinutes": int64(vn(v, "accessTokenTtlMinutes")), "refreshTokenTtlMinutes": int64(vn(v, "refreshTokenTtlMinutes")),
			"reason": vs(v, "reason")}
		return b
	}
	clientFields := func(o jobj) []domain.FormField {
		get := func(path string) any {
			if o == nil {
				return nil
			}
			return at(o, path)
		}
		fields := []domain.FormField{
			withValue(withMax(ff("name", "Name", "text", true), 120), get("name")),
			withValue(withOptions(ff("grantTypes", "Grant types", "multiselect", true), grantTypeOptions), get("grantTypes")),
			withValue(withMax(ff("scopes", "Scopes (one per line)", "textarea", false), 2000), joinLines(strs(o, "scopes"))),
			withValue(withMax(ff("redirectUris", "Redirect URIs (one per line)", "textarea", false), 4000), joinLines(strs(o, "redirectUris"))),
			withValue(withMax(ff("postLogoutRedirectUris", "Post-logout redirect URIs (one per line)", "textarea", false), 4000), joinLines(strs(o, "postLogoutRedirectUris"))),
			withValue(ff("requireConsent", "Ask the user for consent", "toggle", false), boolean(o, "requireConsent")),
			withValue(withPattern(ff("publicRefreshTokens", "Refresh tokens for a public client (mobile apps)", "toggle", false), ``, ""), boolean(o, "publicRefreshTokens")),
			withValue(numberField(ff("accessTokenTtlMinutes", "Access token lifetime (minutes)", "number", true), 1, 60), orDefault(get("accessTokenTtlMinutes"), 5)),
			withValue(numberField(ff("refreshTokenTtlMinutes", "Refresh token lifetime (minutes)", "number", true), 1, 129600), orDefault(get("refreshTokenTtlMinutes"), 43200)),
			reasonField(),
		}
		return fields
	}
	return &staffResource{
		key: "oauth-clients", module: domain.IdentityModule, title: "OAuth clients", status: status, searchPlaceholder: "Search by name or client id",
		columns: []domain.Column{col("name", "Client", "strong", 24), col("clientId", "Client id", "mono", 22), col("type", "Type", "text", 12),
			col("grantTypes", "Grant types", "text", 26), col("status", "Status", "status", 16)},
		idOf: func(o jobj) string { return jstr(o, "clientId") },
		cells: func(o jobj) map[string]any {
			return map[string]any{"status": status(o), "type": cellValue(domain.Humanize(jstr(o, "type")))}
		},
		list:    listAll(load),
		get:     getByPath("/v1/admin/identity/clients/", upIdentity),
		titleOf: func(o jobj) string { return jstr(o, "name") },
		subtitleOf: func(o jobj) string {
			return jstr(o, "clientId") + " · " + domain.Humanize(jstr(o, "type")) + " client"
		},
		sections: func(o jobj) []domain.DetailSection {
			secret := "Public client: no secret"
			if jstr(o, "type") == "confidential" {
				secret = "Stored as a hash; it is shown once when created or rotated"
			}
			return []domain.DetailSection{
				{Title: "Client", Fields: []domain.DetailField{fieldOf("Client id", jstr(o, "clientId"), "mono"), fieldOf("Name", jstr(o, "name"), "text"),
					fieldOf("Type", domain.Humanize(jstr(o, "type")), "text"), fieldOf("Status", map[bool]string{true: "Enabled", false: "Disabled"}[boolean(o, "enabled")], "text"),
					fieldOf("Created", jstr(o, "issuedAt"), "datetime"), fieldOf("Platform client", yesOrNo(boolean(o, "platformClient"))+" (the platform signs in with it; it cannot be disabled)", "text")}},
				{Title: "OAuth", Fields: []domain.DetailField{fieldOf("Grant types", displayValue(o["grantTypes"]), "text"), fieldOf("Scopes", displayValue(o["scopes"]), "text"),
					fieldOf("Redirect URIs", joinLines(strs(o, "redirectUris")), "text"), fieldOf("Post-logout redirect URIs", joinLines(strs(o, "postLogoutRedirectUris")), "text"),
					fieldOf("PKCE required", yesOrNo(boolean(o, "requirePkce")), "text"), fieldOf("Consent screen", yesOrNo(boolean(o, "requireConsent")), "text"),
					fieldOf("Refresh tokens for a public client", yesOrNo(boolean(o, "publicRefreshTokens")), "text")}},
				{Title: "Token lifetimes", Fields: []domain.DetailField{fieldOf("Access token", minutes(o, "accessTokenTtlMinutes"), "text"), fieldOf("Refresh token", minutes(o, "refreshTokenTtlMinutes"), "text"),
					fieldOf("Platform ceiling", "The Settings tab can cap both for every client", "text")}},
				{Title: "Secret", Fields: []domain.DetailField{fieldOf("Secret", secret, "text"), fieldOf("Last rotated", jstr(o, "secretRotatedAt"), "datetime"),
					fieldOf("Previous secret works until", jstr(o, "previousSecretExpiresAt"), "datetime")}},
			}
		},
		actions: []staffAction{
			{key: "rotate-secret", label: "Rotate secret", tone: "neutral", permission: identityAdmin,
				when: func(_ staffCtx, o jobj) string {
					if jstr(o, "type") != "confidential" {
						return "A public client has no secret."
					}
					return ""
				},
				form: func(context.Context, staffCtx, jobj) *domain.Form {
					return &domain.Form{Title: "Rotate the client secret", SubmitLabel: "Rotate secret",
						Intro:  "A new secret is shown once. The old one keeps working for 24 hours so the callers can be updated; then it stops.",
						Fields: []domain.FormField{reasonField()}}
				},
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					var out jobj
					if err := sc.post(ctx, sc.up.Identity, clientPath(o, "/rotate-secret"), jobj{"reason": vs(v, "reason")}, &out); err != nil {
						return err
					}
					sc.showOnce("New client secret", jstr(out, "secret"), "Copy it now. It is not shown again and cannot be read back. The previous secret works for 24 hours.")
					return nil
				}},
			{key: "disable", label: "Disable client", tone: "danger", permission: identityAdmin,
				when: func(_ staffCtx, o jobj) string {
					switch {
					case !boolean(o, "enabled"):
						return "Already disabled."
					case boolean(o, "platformClient"):
						return "The platform signs in with this client; disabling it would lock every staff member out."
					}
					return ""
				},
				form: func(context.Context, staffCtx, jobj) *domain.Form {
					return &domain.Form{Title: "Disable the client", SubmitLabel: "Disable",
						Intro: "The token endpoint refuses the client at once and its refresh tokens stop working. You can enable it again.", Fields: []domain.FormField{reasonField()}}
				},
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.Identity, clientPath(o, "/disable"), jobj{"reason": vs(v, "reason")}, nil)
				}},
			{key: "enable", label: "Enable client", tone: "primary", permission: identityAdmin,
				when: func(_ staffCtx, o jobj) string {
					if boolean(o, "enabled") {
						return "Already enabled."
					}
					return ""
				},
				form: func(context.Context, staffCtx, jobj) *domain.Form {
					return &domain.Form{Title: "Enable the client", SubmitLabel: "Enable", Fields: []domain.FormField{reasonField()}}
				},
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.Identity, clientPath(o, "/enable"), jobj{"reason": vs(v, "reason")}, nil)
				}},
			{key: "revoke-sessions", label: "Revoke all its sign-ins", tone: "danger", permission: identityAdmin,
				form: func(context.Context, staffCtx, jobj) *domain.Form {
					return &domain.Form{Title: "Revoke every sign-in of this client", SubmitLabel: "Revoke all",
						Intro:  "Every refresh token of the client stops working at once; people sign in again. Access tokens already issued expire within minutes.",
						Fields: []domain.FormField{reasonField()}}
				},
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.Identity, clientPath(o, "/sessions/revoke"), jobj{"reason": vs(v, "reason")}, nil)
				}},
		},
		edit: &staffEdit{permission: identityAdmin,
			form: func(o jobj) domain.Form {
				return domain.Form{Title: "Edit the client", SubmitLabel: "Save", Intro: "The client id and type cannot change. Edits made here are never overwritten by a deployment.",
					Fields: clientFields(o)}
			},
			save: func(ctx context.Context, sc staffCtx, o jobj, v jobj, _ string) error {
				return sc.call(ctx, sc.up.Identity, port.StaffCall{Method: http.MethodPut, Path: clientPath(o, ""), Body: body(v)}, nil)
			}},
		create: &staffCreate{permission: identityAdmin,
			form: func(context.Context, staffCtx) domain.Form {
				fields := []domain.FormField{
					withPattern(ff("clientId", "Client id", "text", true), `^[a-z][a-z0-9._-]{2,63}$`, "3 to 64 lower-case letters, digits, dots, dashes or underscores, starting with a letter"),
					withValue(withOptions(ff("type", "Type", "select", true), []domain.FieldOption{{Value: "confidential", Label: "Confidential (has a secret: servers, service accounts)"}, {Value: "public", Label: "Public (PKCE, no secret: browser and mobile apps)"}}), "confidential"),
				}
				return domain.Form{Title: "Register an OAuth client", SubmitLabel: "Register",
					Intro: "A confidential client's secret is shown once, right after you register it.", Fields: append(fields, clientFields(nil)...)}
			},
			create: func(ctx context.Context, sc staffCtx, v jobj) (string, error) {
				b := body(v)
				b["clientId"], b["type"] = vs(v, "clientId"), vs(v, "type")
				var out jobj
				if err := sc.post(ctx, sc.up.Identity, "/v1/admin/identity/clients", b, &out); err != nil {
					return "", err
				}
				if secret := jstr(out, "secret"); secret != "" {
					sc.showOnce("Client secret", secret, "Copy it now. It is not shown again and cannot be read back.")
				}
				return vs(v, "clientId"), nil
			}},
	}
}

func numberField(f domain.FormField, lo, hi float64) domain.FormField {
	f.Min, f.Max = floatPtr(lo), floatPtr(hi)
	return f
}

func orDefault(v any, def float64) any {
	if v == nil {
		return def
	}
	return v
}

// ---------------------------------------------------------------- sessions and tokens

func identitySessionsResource() *staffResource {
	return &staffResource{
		key: "identity-sessions", module: domain.IdentityModule, title: "Sessions & tokens", searchPlaceholder: "Search by user, email or client",
		columns: []domain.Column{col("userName", "User", "strong", 20), col("userEmail", "Email", "text", 22), col("clientName", "App", "text", 16),
			col("ipAddress", "IP address", "mono", 12), col("lastUsedAt", "Last used", "datetime", 15), col("refreshable", "Refresh token", "text", 15)},
		list: listPaged("/v1/admin/identity/sessions", upIdentity, nil),
		get:  getByPath("/v1/admin/identity/sessions/", upIdentity),
		cells: func(o jobj) map[string]any {
			// Service accounts have no person behind them: the client id stands in for the name.
			return map[string]any{"userName": cellValue(firstNonEmpty(jstr(o, "userName"), "Service account "+jstr(o, "clientId")))}
		},
		titleOf: func(o jobj) string { return firstNonEmpty(jstr(o, "userName"), "Service account "+jstr(o, "clientId")) },
		subtitleOf: func(o jobj) string {
			return strings.TrimSpace(firstNonEmpty(jstr(o, "userEmail"), jstr(o, "userId")) + " · " + jstr(o, "clientName"))
		},
		sections: func(o jobj) []domain.DetailSection {
			return []domain.DetailSection{{Title: "Sign-in", Fields: []domain.DetailField{
				fieldOf("User", jstr(o, "userName"), "text"), fieldOf("Email", jstr(o, "userEmail"), "text"), fieldOf("User id", jstr(o, "userId"), "mono"),
				fieldOf("App (client)", jstr(o, "clientName")+" ("+jstr(o, "clientId")+")", "text"), fieldOf("IP address", jstr(o, "ipAddress"), "mono"),
				fieldOf("Signed in", jstr(o, "signedInAt"), "datetime"), fieldOf("Last used", jstr(o, "lastUsedAt"), "datetime"),
				fieldOf("Expires", jstr(o, "expiresAt"), "datetime"), fieldOf("Refresh token", yesOrNo(boolean(o, "refreshable")), "text")}}}
		},
		actions: []staffAction{
			{key: "revoke", label: "Revoke this sign-in", tone: "danger", permission: identityAdmin, removes: true,
				form: func(context.Context, staffCtx, jobj) *domain.Form {
					return &domain.Form{Title: "Revoke this sign-in", SubmitLabel: "Revoke",
						Intro: "Its refresh token stops working at once. An access token already issued expires within minutes.", Fields: []domain.FormField{reasonField()}}
				},
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.Identity, "/v1/admin/identity/sessions/"+url.PathEscape(jstr(o, "id"))+"/revoke", jobj{"reason": vs(v, "reason")}, nil)
				}},
			{key: "revoke-user", label: "Sign the user out everywhere", tone: "danger", permission: identityAdmin, removes: true,
				when: func(_ staffCtx, o jobj) string {
					if jstr(o, "userName") == "" && jstr(o, "userEmail") == "" {
						return "This sign-in belongs to a service account, not a person."
					}
					return ""
				},
				form: func(context.Context, staffCtx, jobj) *domain.Form {
					return &domain.Form{Title: "Sign the user out everywhere", SubmitLabel: "Sign out everywhere",
						Intro: "Every sign-in of this person on every app stops working, and their single sign-on browser sessions end.", Fields: []domain.FormField{reasonField()}}
				},
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.Identity, "/v1/admin/identity/users/"+url.PathEscape(jstr(o, "userId"))+"/sessions/revoke", jobj{"reason": vs(v, "reason")}, nil)
				}},
		},
	}
}

// ---------------------------------------------------------------- signing keys

func signingKeysResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, _ staffQuery) ([]jobj, error) {
		var raw any
		if err := sc.get(ctx, sc.up.Identity, "/v1/admin/identity/signing-keys", nil, &raw); err != nil {
			return nil, err
		}
		return jpage(raw).items, nil
	}
	list := listAll(load)
	meaning := map[string]string{
		"ACTIVE":   "Signs every new token. Resource servers find its public half in the JWKS.",
		"RETIRING": "No longer signs. Its public half stays in the JWKS so tokens it signed keep verifying, until the longest of them has expired.",
		"RETIRED":  "Gone from the JWKS. Tokens it signed no longer verify.",
	}
	return &staffResource{
		key: "signing-keys", module: domain.IdentityModule, title: "Signing keys", statusKey: "status",
		columns: []domain.Column{col("kid", "Key id (kid)", "mono", 28), col("algorithm", "Algorithm", "text", 10), col("status", "Status", "status", 14),
			col("createdAt", "Created", "datetime", 16), col("retireAfter", "Retires after", "datetime", 16)},
		idOf: func(o jobj) string { return jstr(o, "kid") },
		list: list, get: findIn(list, "kid"),
		titleOf:    func(o jobj) string { return jstr(o, "kid") },
		subtitleOf: func(o jobj) string { return jstr(o, "algorithm") + " · " + domain.Humanize(jstr(o, "status")) },
		sections: func(o jobj) []domain.DetailSection {
			return []domain.DetailSection{{Title: "Key", Fields: []domain.DetailField{fieldOf("Key id (kid)", jstr(o, "kid"), "mono"), fieldOf("Algorithm", jstr(o, "algorithm"), "text"),
				fieldOf("Status", domain.Humanize(jstr(o, "status")), "text"), fieldOf("What it means", meaning[jstr(o, "status")], "text"),
				fieldOf("Created", jstr(o, "createdAt"), "datetime"), fieldOf("Rotated out", jstr(o, "rotatedAt"), "datetime"),
				fieldOf("Leaves the JWKS", jstr(o, "retireAfter"), "datetime"), fieldOf("Retired", jstr(o, "retiredAt"), "datetime")}}}
		},
		create: &staffCreate{permission: identityAdmin,
			form: func(context.Context, staffCtx) domain.Form {
				return domain.Form{Title: "Rotate the signing key", SubmitLabel: "Rotate",
					Intro:  "A new key signs every new token from now on. The current key stays published until the longest access token issued under it has expired, then retires. Nothing needs redeploying.",
					Fields: []domain.FormField{reasonField()}}
			},
			create: func(ctx context.Context, sc staffCtx, v jobj) (string, error) {
				var out jobj
				if err := sc.post(ctx, sc.up.Identity, "/v1/admin/identity/signing-keys/rotate", jobj{"reason": vs(v, "reason")}, &out); err != nil {
					return "", err
				}
				return jstr(out, "kid"), nil
			}},
	}
}

// ---------------------------------------------------------------- authentication settings

type authSettingSpec struct {
	key, label string
	// toggle fields are booleans; the rest are whole numbers.
	toggle   bool
	lo, hi   float64
	optional bool
	hint     string
}

var authSettingSpecs = []authSettingSpec{
	{key: "passwordMinLength", label: "Shortest password (characters)", lo: 8, hi: 64},
	{key: "mfaRequiredAccount", label: "Require a second factor of customers", toggle: true},
	{key: "mfaRequiredSeller", label: "Require a second factor of seller members", toggle: true},
	{key: "mfaRequiredProvider", label: "Require a second factor of service-provider members", toggle: true},
	{key: "sessionIdleTimeoutMinutes", label: "Sign-in session idle timeout (minutes)", lo: 5, hi: 1440},
	{key: "sessionAbsoluteTimeoutMinutes", label: "Sign-in session absolute timeout (minutes)", lo: 60, hi: 43200},
	{key: "accessTokenTtlCapMinutes", label: "Access token lifetime ceiling (minutes, blank = none)", lo: 1, hi: 60, optional: true},
	{key: "refreshTokenTtlCapMinutes", label: "Refresh token lifetime ceiling (minutes, blank = none)", lo: 5, hi: 129600, optional: true},
}

func authSettingsResource() *staffResource {
	get := func(ctx context.Context, sc staffCtx, _ string) (jobj, error) {
		var o jobj
		if err := sc.get(ctx, sc.up.Identity, "/v1/admin/identity/settings", nil, &o); err != nil {
			return nil, err
		}
		o["id"] = "settings"
		o["setting"] = "Sign-in, password and token settings"
		return o, nil
	}
	describe := func(o jobj, s authSettingSpec) string {
		val, def := at(o, "effective."+s.key), at(o, "defaults."+s.key)
		show := func(v any) string {
			switch x := v.(type) {
			case nil:
				return "none"
			case bool:
				return yesOrNo(x)
			default:
				return displayValue(x)
			}
		}
		out := show(val)
		if at(o, "overrides."+s.key) != nil {
			out += " (set here; default " + show(def) + ")"
		} else {
			out += " (default)"
		}
		return out
	}
	return &staffResource{
		key: "auth-settings", module: domain.IdentityModule, title: "Settings",
		columns: []domain.Column{col("setting", "Setting", "strong", 34), col("effective.passwordMinLength", "Password min.", "number", 12),
			col("effective.sessionIdleTimeoutMinutes", "Idle (min)", "number", 12), col("effective.sessionAbsoluteTimeoutMinutes", "Absolute (min)", "number", 14),
			col("effective.accessTokenTtlCapMinutes", "Access cap (min)", "number", 14), col("effective.refreshTokenTtlCapMinutes", "Refresh cap (min)", "number", 14)},
		list: func(ctx context.Context, sc staffCtx, _ staffQuery) (staffList, error) {
			o, err := get(ctx, sc, "settings")
			if err != nil {
				return staffList{}, err
			}
			one := int64(1)
			return staffList{items: []jobj{o}, total: &one}, nil
		},
		get:        get,
		titleOf:    func(jobj) string { return "Sign-in, password and token settings" },
		subtitleOf: func(jobj) string { return "Stored in the identity database; the configured values are the defaults" },
		sections: func(o jobj) []domain.DetailSection {
			fields := []domain.DetailField{}
			for _, s := range authSettingSpecs {
				fields = append(fields, fieldOf(s.label, describe(o, s), "text"))
			}
			return []domain.DetailSection{{Title: "In force", Fields: fields},
				{Title: "How it applies", Fields: []domain.DetailField{
					fieldOf("Passwords", "Checked on the next sign-up, password change or reset.", "text"),
					fieldOf("Second factor", "Staff always need one. For the other portals it applies to people who have a factor set up or a phone on file.", "text"),
					fieldOf("Session timeouts", "Apply to the sign-in (single sign-on) session at its next request.", "text"),
					fieldOf("Token ceilings", "Cap every client's own lifetime; they apply to tokens issued from now on.", "text")}}}
		},
		edit: &staffEdit{permission: identityAdmin,
			form: func(o jobj) domain.Form {
				fields := []domain.FormField{}
				for _, s := range authSettingSpecs {
					f := ff(s.key, s.label, "number", !s.optional)
					if s.toggle {
						f = ff(s.key, s.label, "toggle", false)
					} else {
						f = numberField(f, s.lo, s.hi)
					}
					fields = append(fields, withValue(f, at(o, "effective."+s.key)))
				}
				return domain.Form{Title: "Change the settings", SubmitLabel: "Save settings",
					Intro:  "A value equal to the configured default is stored as \"use the default\". The change is recorded with your reason and what it was before.",
					Fields: append(fields, reasonField())}
			},
			save: func(ctx context.Context, sc staffCtx, o jobj, v jobj, _ string) error {
				overrides := jobj{}
				for _, s := range authSettingSpecs {
					var val any
					switch {
					case s.toggle:
						val = vb(v, s.key)
					case v[s.key] != nil && v[s.key] != "":
						val = int(vn(v, s.key))
					}
					// Only what differs from the configured default is stored; the rest keeps following the default.
					if def := at(o, "defaults."+s.key); val != nil && !sameSetting(val, def) {
						overrides[s.key] = val
					}
				}
				return sc.call(ctx, sc.up.Identity, port.StaffCall{Method: http.MethodPut, Path: "/v1/admin/identity/settings",
					Body: jobj{"overrides": overrides, "reason": vs(v, "reason")}}, nil)
			}},
	}
}

func sameSetting(val, def any) bool {
	switch d := def.(type) {
	case float64:
		n, ok := val.(int)
		return ok && float64(n) == d
	case bool:
		b, ok := val.(bool)
		return ok && b == d
	default:
		return false
	}
}
