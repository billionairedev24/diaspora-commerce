package app

import (
	"context"
	"net/http"
	"net/url"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Sign-out policy (Developer tools › Identity & access › Sign-out): when each audience is signed out for inactivity and
// for session length. Identity stores the overrides, validates them (the messages come back on the form's fields), asks
// for a reason and a step-up, and writes the access change log; the BFF enforces the result (session_enforce.go).

const sessionPolicyBase = "/v1/admin/identity/session-policy"

// Form keys are identity's field names, so its validation messages land on the right field.
var sessionPolicyFields = []struct {
	key, label, help string
	lo, hi           float64
}{
	{"idleTimeoutMinutes", "Sign out after this many minutes without activity", "1 minute to 180 days (259200 minutes). Reading a page and background refreshes do not count as activity.", 1, 180 * 24 * 60},
	{"absoluteTimeoutMinutes", "Sign out this many minutes after signing in, however active", "5 minutes to 365 days (525600 minutes). Not shorter than the idle time.", 5, 365 * 24 * 60},
	{"warnSeconds", "Warn this many seconds before signing out", "0 to 300. 0 means no warning. Must be shorter than the idle time.", 0, 300},
}

// seconds renders a warning period: "None" for 0.
func seconds(o jobj, path string) string {
	n := num(o, path)
	if n == 0 {
		return "None"
	}
	return displayValue(n) + " seconds"
}

func sessionPolicyResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx) ([]jobj, error) {
		var raw any
		if err := sc.get(ctx, sc.up.Identity, sessionPolicyBase, nil, &raw); err != nil {
			return nil, err
		}
		items := jpage(raw).items
		for _, o := range items {
			o["name"] = jstr(o, "label")
		}
		return items, nil
	}
	describe := func(o jobj, key string) string {
		val, def := at(o, "effective."+key), at(o, "defaults."+key)
		if at(o, "overrides."+key) != nil {
			return displayValue(val) + " (set here; default " + displayValue(def) + ")"
		}
		return displayValue(val) + " (default)"
	}
	return &staffResource{
		key: "session-policy", module: domain.IdentityModule, title: "Sign-out",
		columns: []domain.Column{col("name", "Who", "strong", 30), col("idle", "Idle timeout", "text", 16),
			col("absolute", "Session length", "text", 16), col("warn", "Warning", "text", 14)},
		idOf: func(o jobj) string { return jstr(o, "audience") },
		cells: func(o jobj) map[string]any {
			return map[string]any{"idle": minutes(o, "effective.idleTimeoutMinutes"), "absolute": minutes(o, "effective.absoluteTimeoutMinutes"),
				"warn": seconds(o, "effective.warnSeconds")}
		},
		list: func(ctx context.Context, sc staffCtx, _ staffQuery) (staffList, error) {
			items, err := load(ctx, sc)
			if err != nil {
				return staffList{}, err
			}
			n := int64(len(items))
			return staffList{items: items, total: &n}, nil
		},
		get: func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
			items, err := load(ctx, sc)
			if err != nil {
				return nil, err
			}
			for _, o := range items {
				if jstr(o, "audience") == id {
					o["id"] = id
					return o, nil
				}
			}
			return nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such audience.")
		},
		titleOf:    func(o jobj) string { return jstr(o, "label") },
		subtitleOf: func(jobj) string { return "When people are signed out for inactivity and for session length" },
		sections: func(o jobj) []domain.DetailSection {
			return []domain.DetailSection{
				{Title: "In force", Fields: []domain.DetailField{
					fieldOf("Idle timeout (minutes)", describe(o, "idleTimeoutMinutes"), "text"),
					fieldOf("Session length (minutes)", describe(o, "absoluteTimeoutMinutes"), "text"),
					fieldOf("Warning (seconds)", describe(o, "warnSeconds"), "text")}},
				{Title: "How it applies", Fields: []domain.DetailField{
					fieldOf("Idle timeout", "Counted from the person's last real activity. Pages that refresh themselves do not count. The web apps warn before it and sign the person out at the end.", "text"),
					fieldOf("Session length", "Counted from sign-in, however active the person is. Signing in again starts a new one.", "text"),
					fieldOf("When it starts", "A change reaches every server within a minute and applies to people already signed in. The mobile apps read it when they open.", "text")}},
			}
		},
		edit: &staffEdit{permission: identityAdmin,
			form: func(o jobj) domain.Form {
				fields := make([]domain.FormField, 0, len(sessionPolicyFields)+2)
				keys := make([]string, 0, len(sessionPolicyFields))
				for _, f := range sessionPolicyFields {
					field := numberField(ff(f.key, f.label, "number", true), f.lo, f.hi)
					field.Help = f.help
					fields = append(fields, withValue(field, at(o, "effective."+f.key)))
					keys = append(keys, f.key)
				}
				return domain.Form{Title: "Change when " + jstr(o, "label") + " are signed out", SubmitLabel: "Save",
					Intro:      "A value equal to the standard setting is stored as \"use the standard setting\". The change is recorded with your reason and what it was before.",
					Fields:     append(fields, reasonField(), stepUpField()),
					ChangeKeys: keys}
			},
			save: func(ctx context.Context, sc staffCtx, o jobj, v jobj, _ string) error {
				overrides := jobj{}
				for _, f := range sessionPolicyFields {
					if v[f.key] != nil && v[f.key] != "" {
						overrides[f.key] = int(vn(v, f.key))
					}
				}
				// Identity drops the values that equal the defaults, so the rest keeps following them.
				return sc.dangerous(ctx, v, port.StaffCall{Method: http.MethodPut, Path: sessionPolicyBase + "/" + url.PathEscape(jstr(o, "audience")),
					Body: jobj{"overrides": overrides, "reason": vs(v, "reason")}}, nil)
			}},
	}
}
