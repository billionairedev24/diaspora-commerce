package app

import (
	"context"
	"net/http"
	"net/url"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Public content that staff used to need a developer for: the open roles on the careers page and the cookie table that
// the cookie notice and the cookie banner list. Both are content-service APIs that existed without a screen.

// ---------------------------------------------------------------- careers: open roles (content)

var jobStatusOptions = []domain.FieldOption{{Value: "DRAFT", Label: "Draft (not on the careers page)"}, {Value: "OPEN", Label: "Open (listed, takes applications)"}, {Value: "CLOSED", Label: "Closed"}}

// linesOf turns a bullet list into the text of a textarea, one bullet per line.
func linesOf(o jobj, key string) string {
	var out []string
	if l, ok := o[key].([]any); ok {
		for _, it := range l {
			if s, ok := it.(string); ok {
				out = append(out, s)
			}
		}
	}
	return strings.Join(out, "\n")
}

// textLines is the inverse: the non-empty lines of a textarea.
func textLines(text string) []string {
	out := []string{}
	for _, line := range strings.Split(strings.ReplaceAll(text, "\r\n", "\n"), "\n") {
		if s := strings.TrimSpace(strings.TrimLeft(strings.TrimSpace(line), "-•* ")); s != "" {
			out = append(out, s)
		}
	}
	return out
}

func jobFields(o jobj) []domain.FormField {
	return []domain.FormField{
		withValue(withMax(ff("title", "Role title", "text", true), 120), jstr(o, "title")),
		withValue(withMax(ff("team", "Team", "text", true), 60), jstr(o, "team")),
		withValue(withMax(ff("location", "Location", "text", true), 80), jstr(o, "location")),
		withValue(withMax(ff("type", "Type (Full-time, Hourly…)", "text", true), 40), firstNonEmpty(jstr(o, "type"), "Full-time")),
		withValue(withMax(ff("pay", "Pay range shown to candidates", "text", true), 60), jstr(o, "pay")),
		withValue(withOptions(ff("status", "Status", "select", true), jobStatusOptions), firstNonEmpty(jstr(o, "status"), "DRAFT")),
		withValue(withMax(ff("summary", "About the role", "textarea", true), 4000), jstr(o, "summary")),
		withValue(ff("responsibilities", "What you will do (one per line)", "textarea", false), linesOf(o, "responsibilities")),
		withValue(ff("requirements", "What you bring (one per line)", "textarea", false), linesOf(o, "requirements")),
		withValue(withRange(ff("sortOrder", "Order in the list", "number", false), 1000), num(o, "sortOrder")),
	}
}

func jobBody(v jobj) jobj {
	return jobj{"title": vs(v, "title"), "team": vs(v, "team"), "location": vs(v, "location"), "type": vs(v, "type"), "pay": vs(v, "pay"), "summary": vs(v, "summary"),
		"responsibilities": textLines(vs(v, "responsibilities")), "requirements": textLines(vs(v, "requirements")), "status": vs(v, "status"), "sortOrder": int(vn(v, "sortOrder"))}
}

func careersJobsResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
		var raw any
		if err := sc.get(ctx, sc.up.Content, "/v1/careers/admin/jobs", nil, &raw); err != nil {
			return nil, err
		}
		out := []jobj{}
		for _, j := range jpage(raw).items {
			if q.Status == "" || jstr(j, "status") == q.Status {
				j["path"] = "/careers"
				j["href"] = sc.storefrontLink("/careers")
				out = append(out, j)
			}
		}
		return out, nil
	}
	list := listAll(load)
	setStatus := func(status string) func(context.Context, staffCtx, jobj, jobj) error {
		return func(ctx context.Context, sc staffCtx, o jobj, _ jobj) error {
			body := jobj{"title": jstr(o, "title"), "team": jstr(o, "team"), "location": jstr(o, "location"), "type": jstr(o, "type"), "pay": jstr(o, "pay"), "summary": jstr(o, "summary"),
				"responsibilities": o["responsibilities"], "requirements": o["requirements"], "status": status, "sortOrder": int(num(o, "sortOrder"))}
			return sc.call(ctx, sc.up.Content, port.StaffCall{Method: http.MethodPut, Path: "/v1/careers/jobs/" + url.PathEscape(jstr(o, "id")), Body: body}, nil)
		}
	}
	return &staffResource{
		key: "careers-jobs", module: "siteCfg", title: "Careers", statusKey: "status",
		columns: []domain.Column{col("title", "Role", "strong", 30), col("team", "Team", "text", 16), col("location", "Location", "text", 18), col("type", "Type", "text", 12),
			col("status", "Status", "status", 12), col("postedAt", "Opened", "date", 12)},
		facets:     domain.StaffFacets("OPEN", "DRAFT", "CLOSED"),
		list:       list,
		get:        findIn(list, "id"),
		titleOf:    func(o jobj) string { return jstr(o, "title") },
		subtitleOf: func(o jobj) string { return jstr(o, "team") + " · " + jstr(o, "location") },
		sections: func(o jobj) []domain.DetailSection {
			return autoSections(o, "status", "responsibilities", "requirements", "path", "href", "id")
		},
		tables: func(o jobj) []domain.DetailTable {
			rows := func(key string) []jobj {
				out := []jobj{}
				for _, line := range textLines(linesOf(o, key)) {
					out = append(out, jobj{"text": line})
				}
				return out
			}
			return []domain.DetailTable{
				tableOf("What you will do", rows("responsibilities"), []domain.Column{col("text", "Responsibility", "text", 100)}, "text"),
				tableOf("What you bring", rows("requirements"), []domain.Column{col("text", "Requirement", "text", 100)}, "text")}
		},
		links: func(o jobj) []domain.RecordLink {
			if jstr(o, "status") != "OPEN" {
				return nil
			}
			return []domain.RecordLink{{Label: "Open the careers page", Href: "/careers", Storefront: true}}
		},
		actions: []staffAction{
			{key: "open", label: "Open the role", tone: "primary", permission: "content:careers.edit", confirm: "List this role on the careers page and accept applications?",
				when: inState("DRAFT", "CLOSED"), run: setStatus("OPEN")},
			{key: "close", label: "Close the role", tone: "danger", permission: "content:careers.edit", confirm: "Take this role off the careers page? Applications already received stay.",
				when: inState("OPEN"), run: setStatus("CLOSED")},
		},
		edit: &staffEdit{permission: "content:careers.edit",
			form: func(o jobj) domain.Form {
				return domain.Form{Title: "Edit the role", SubmitLabel: "Save", Fields: jobFields(o)}
			},
			save: func(ctx context.Context, sc staffCtx, o jobj, v jobj, _ string) error {
				return sc.call(ctx, sc.up.Content, port.StaffCall{Method: http.MethodPut, Path: "/v1/careers/jobs/" + url.PathEscape(jstr(o, "id")), Body: jobBody(v)}, nil)
			}},
		create: &staffCreate{permission: "content:careers.edit",
			form: func(context.Context, staffCtx) domain.Form {
				fields := append([]domain.FormField{withPattern(withMax(ff("id", "Address (/careers…)", "text", true), 60), `^[a-z0-9][a-z0-9-]{2,59}$`,
					"Lower-case letters, digits and dashes, e.g. data-engineer-remote-us")}, jobFields(jobj{})...)
				return domain.Form{Title: "New role", SubmitLabel: "Create role", Fields: fields}
			},
			create: func(ctx context.Context, sc staffCtx, v jobj) (string, error) {
				id := vs(v, "id")
				var out jobj
				if err := sc.post(ctx, sc.up.Content, "/v1/careers/jobs", jobj{"id": id, "job": jobBody(v)}, &out); err != nil {
					return "", err
				}
				return id, nil
			}},
	}
}

// ---------------------------------------------------------------- cookie table (content)

func cookieDefinitionsResource() *staffResource {
	policy := func(ctx context.Context, sc staffCtx) (jobj, error) {
		var p jobj
		if err := sc.get(ctx, sc.up.Content, "/v1/consent/cookie-policy", nil, &p); err != nil {
			return nil, err
		}
		return p, nil
	}
	categories := func(p jobj) []domain.FieldOption {
		out := []domain.FieldOption{}
		for _, c := range objs(p, "categories") {
			out = append(out, domain.FieldOption{Value: jstr(c, "key"), Label: jstr(c, "name")})
		}
		return out
	}
	load := func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
		p, err := policy(ctx, sc)
		if err != nil {
			return nil, err
		}
		names := map[string]string{}
		for _, c := range categories(p) {
			names[c.Value] = c.Label
		}
		out := []jobj{}
		for _, c := range objs(p, "cookies") {
			c["categoryName"] = firstNonEmpty(names[jstr(c, "category")], domain.Humanize(jstr(c, "category")))
			c["path"] = "/legal/cookies"
			out = append(out, c)
		}
		return out, nil
	}
	list := listAll(load)
	fields := func(ctx context.Context, sc staffCtx, o jobj, withName bool) []domain.FormField {
		opts := []domain.FieldOption{}
		if p, err := policy(ctx, sc); err == nil {
			opts = categories(p)
		}
		out := []domain.FormField{}
		if withName {
			out = append(out, withPattern(withMax(ff("name", "Cookie name", "text", true), 80), `^[A-Za-z0-9_.\-]+$`, "Letters, digits, dots, dashes and underscores, exactly as the browser shows it"))
		}
		return append(out,
			withValue(withMax(ff("provider", "Who sets it", "text", true), 80), jstr(o, "provider")),
			withValue(withMax(ff("purpose", "What it is for", "textarea", true), 500), jstr(o, "purpose")),
			withValue(withMax(ff("duration", "How long it lives", "text", true), 40), jstr(o, "duration")),
			withValue(withOptions(ff("category", "Category", "select", true), opts), jstr(o, "category")))
	}
	save := func(name string, v jobj) port.StaffCall {
		return port.StaffCall{Method: http.MethodPut, Path: "/v1/consent/cookies/" + url.PathEscape(name),
			Body: jobj{"provider": vs(v, "provider"), "purpose": vs(v, "purpose"), "duration": vs(v, "duration"), "category": vs(v, "category")}}
	}
	return &staffResource{
		key: "cookie-definitions", module: "siteCfg", title: "Cookies",
		columns:    []domain.Column{col("name", "Cookie", "mono", 22), col("provider", "Set by", "text", 18), col("categoryName", "Category", "text", 16), col("duration", "Lives", "text", 12), col("purpose", "Purpose", "text", 32)},
		idOf:       func(o jobj) string { return jstr(o, "name") },
		list:       list,
		get:        findIn(list, "name"),
		titleOf:    func(o jobj) string { return jstr(o, "name") },
		subtitleOf: func(o jobj) string { return jstr(o, "provider") + " · " + jstr(o, "categoryName") },
		sections:   func(o jobj) []domain.DetailSection { return autoSections(o, "", "path", "category") },
		links: func(jobj) []domain.RecordLink {
			return []domain.RecordLink{{Label: "Open the cookie notice", Href: "/legal/cookies", Storefront: true}}
		},
		actions: []staffAction{
			{key: "delete", label: "Remove from the table", tone: "danger", permission: "content:legal.edit", confirm: "Remove this cookie from the table on the cookie notice?", removes: true,
				run: func(ctx context.Context, sc staffCtx, o jobj, _ jobj) error {
					return sc.call(ctx, sc.up.Content, port.StaffCall{Method: http.MethodDelete, Path: "/v1/consent/cookies/" + url.PathEscape(jstr(o, "name"))}, nil)
				}},
		},
		edit: &staffEdit{permission: "content:legal.edit",
			form: func(o jobj) domain.Form {
				// The category list is read when the form opens (the sheet builds it per record).
				return domain.Form{Title: "Edit cookie", SubmitLabel: "Save", Fields: []domain.FormField{
					withValue(withMax(ff("provider", "Who sets it", "text", true), 80), jstr(o, "provider")),
					withValue(withMax(ff("purpose", "What it is for", "textarea", true), 500), jstr(o, "purpose")),
					withValue(withMax(ff("duration", "How long it lives", "text", true), 40), jstr(o, "duration")),
					withValue(withOptions(ff("category", "Category", "select", true), cookieCategoryOptions), jstr(o, "category"))}}
			},
			save: func(ctx context.Context, sc staffCtx, o jobj, v jobj, _ string) error {
				return sc.call(ctx, sc.up.Content, save(jstr(o, "name"), v), nil)
			}},
		create: &staffCreate{permission: "content:legal.edit",
			form: func(ctx context.Context, sc staffCtx) domain.Form {
				return domain.Form{Title: "Add a cookie", SubmitLabel: "Add cookie", Fields: fields(ctx, sc, jobj{}, true)}
			},
			create: func(ctx context.Context, sc staffCtx, v jobj) (string, error) {
				name := vs(v, "name")
				return name, sc.call(ctx, sc.up.Content, save(name, v), nil)
			}},
	}
}

// cookieCategoryOptions are content's seeded cookie categories (necessary, analytics, advertising, preferences); the
// edit form of a cookie offers them, and content refuses a key it does not know.
var cookieCategoryOptions = []domain.FieldOption{{Value: "necessary", Label: "Strictly necessary"}, {Value: "functional", Label: "Functional"},
	{Value: "analytics", Label: "Analytics"}, {Value: "advertising", Label: "Advertising"}}
