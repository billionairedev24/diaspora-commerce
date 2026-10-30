package app

import (
	"context"
	"net/http"
	"net/url"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ---------------------------------------------------------------- help center articles (content)

var helpTopicOptions = []domain.FieldOption{
	{Value: "ORDERS_DELIVERY", Label: "Orders & delivery"}, {Value: "RETURNS_REFUNDS", Label: "Returns & refunds"},
	{Value: "ACCOUNT_PAYMENTS", Label: "Your account & payments"}, {Value: "SELLING", Label: "Selling & advertising"},
	{Value: "COMPANY", Label: "About AfroTransact"},
}

var helpStatusOptions = []domain.FieldOption{{Value: "PUBLISHED", Label: "Published"}, {Value: "DRAFT", Label: "Draft (staff only)"}}

// helpSectionsMarkdown writes an article's sections as one Markdown text: "## Heading" then the body, per section.
func helpSectionsMarkdown(o jobj) string {
	var b strings.Builder
	for i, s := range objs(o, "sections") {
		if i > 0 {
			b.WriteString("\n\n")
		}
		b.WriteString("## " + jstr(s, "heading") + "\n\n" + strings.TrimSpace(jstr(s, "body")))
	}
	return b.String()
}

// parseHelpSections splits the staff form's Markdown back into sections at each "## " heading; text before the first
// heading is not allowed (content needs a heading per section).
func parseHelpSections(markdown string) ([]jobj, map[string]string) {
	var out []jobj
	var body []string
	flush := func() {
		if len(out) > 0 {
			out[len(out)-1]["body"] = strings.TrimSpace(strings.Join(body, "\n"))
		}
		body = nil
	}
	for _, line := range strings.Split(strings.ReplaceAll(markdown, "\r\n", "\n"), "\n") {
		if h, ok := strings.CutPrefix(strings.TrimSpace(line), "## "); ok {
			flush()
			out = append(out, jobj{"heading": strings.TrimSpace(h)})
			continue
		}
		if len(out) == 0 && strings.TrimSpace(line) != "" {
			return nil, map[string]string{"sections": "Start each section with a “## Heading” line"}
		}
		body = append(body, line)
	}
	flush()
	if len(out) == 0 {
		return nil, map[string]string{"sections": "Add at least one “## Heading” section"}
	}
	return out, nil
}

func helpArticleFields(o jobj) []domain.FormField {
	return []domain.FormField{
		withValue(withMax(ff("title", "Title", "text", true), 120), jstr(o, "title")),
		withValue(withMax(ff("summary", "Summary (shown in the help center list)", "text", true), 300), jstr(o, "summary")),
		withValue(withOptions(ff("topic", "Topic", "select", true), helpTopicOptions), firstNonEmpty(jstr(o, "topic"), "ORDERS_DELIVERY")),
		withValue(withOptions(ff("status", "Status", "select", true), helpStatusOptions), firstNonEmpty(jstr(o, "status"), "DRAFT")),
		withValue(withRange(ff("sortOrder", "Order in its topic", "number", false), 1000), num(o, "sortOrder")),
		sectionsField(helpSectionsMarkdown(o)),
	}
}

func sectionsField(value string) domain.FormField {
	f := withValue(ff("sections", "Page (Markdown: a “## Heading” line before each section)", "textarea", true), value)
	f.Help = "Paragraphs, “-” lists, **bold** and [links](/orders); email addresses become links."
	return f
}

func helpArticleBody(v jobj, expected *int64) (jobj, error) {
	sections, errs := parseHelpSections(vs(v, "sections"))
	if errs != nil {
		return nil, domain.ValidationProblem(errs)
	}
	body := jobj{"topic": vs(v, "topic"), "title": vs(v, "title"), "summary": vs(v, "summary"), "status": vs(v, "status"),
		"sortOrder": int(vn(v, "sortOrder")), "sections": sections}
	if expected != nil {
		body["expectedVersion"] = *expected
	}
	return body, nil
}

// helpPath is the storefront path of a help page.
func helpPath(slug string) string {
	if slug == domain.HelpHub {
		return "/help"
	}
	return "/help/" + slug
}

func helpArticlesResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
		var raw any
		if err := sc.get(ctx, sc.up.Content, "/v1/help/admin/articles", nil, &raw); err != nil {
			return nil, err
		}
		out := []jobj{}
		for _, a := range jpage(raw).items {
			if q.Status == "" || jstr(a, "status") == q.Status {
				a["path"] = helpPath(jstr(a, "slug"))
				a["href"] = sc.storefrontLink(jstr(a, "path"))
				out = append(out, a)
			}
		}
		return out, nil
	}
	list := listAll(load)
	return &staffResource{
		key: "help-articles", module: "siteCfg", title: "Help center", statusKey: "status",
		columns: []domain.Column{col("title", "Page", "strong", 30), col("slug", "Address", "mono", 22), col("topicTitle", "Topic", "text", 20),
			col("status", "Status", "status", 13), col("updatedAt", "Updated", "date", 15)},
		facets:     domain.StaffFacets("PUBLISHED", "DRAFT"),
		idOf:       func(o jobj) string { return jstr(o, "slug") },
		list:       list,
		get:        findIn(list, "slug"),
		titleOf:    func(o jobj) string { return jstr(o, "title") },
		subtitleOf: func(o jobj) string { return jstr(o, "topicTitle") + " · " + jstr(o, "path") },
		sections: func(o jobj) []domain.DetailSection {
			return autoSections(o, "status", "sections", "path", "version", "topic")
		},
		tables: func(o jobj) []domain.DetailTable {
			return []domain.DetailTable{tableOf("Sections", objs(o, "sections"), []domain.Column{col("heading", "Heading", "strong", 30), col("body", "Text (Markdown)", "text", 70)}, "heading")}
		},
		links: func(o jobj) []domain.RecordLink {
			links := []domain.RecordLink{{Label: "Preview, edit and publish", Href: "/settings/pages/help/" + jstr(o, "slug")}}
			if jstr(o, "status") == "PUBLISHED" {
				links = append(links, domain.RecordLink{Label: "Open the live page", Href: jstr(o, "path"), Storefront: true})
			}
			return links
		},
		version: func(o jobj) string { return jstr(o, "version") },
		edit: &staffEdit{permission: "content:site.edit",
			form: func(o jobj) domain.Form {
				return domain.Form{Title: "Edit help page", SubmitLabel: "Save", Fields: helpArticleFields(o)}
			},
			save: func(ctx context.Context, sc staffCtx, o jobj, v jobj, _ string) error {
				version := int64(num(o, "version"))
				body, err := helpArticleBody(v, &version)
				if err != nil {
					return err
				}
				return sc.call(ctx, sc.up.Content, port.StaffCall{Method: http.MethodPut, Path: "/v1/help/articles/" + url.PathEscape(jstr(o, "slug")), Body: body}, nil)
			}},
		create: &staffCreate{permission: "content:site.edit",
			form: func(context.Context, staffCtx) domain.Form {
				fields := append([]domain.FormField{withPattern(withMax(ff("slug", "Address (/help/…)", "text", true), 60), `^[a-z0-9][a-z0-9-]{1,59}$`,
					"Lower-case letters, digits and dashes, e.g. track-a-package")}, helpArticleFields(jobj{})...)
				return domain.Form{Title: "New help page", SubmitLabel: "Create page", Fields: fields}
			},
			create: func(ctx context.Context, sc staffCtx, v jobj) (string, error) {
				slug := vs(v, "slug")
				if !domain.IsHelpSlug(slug) {
					return "", domain.ValidationProblem(map[string]string{"slug": "Lower-case letters, digits and dashes, e.g. track-a-package"})
				}
				var existing any
				if sc.get(ctx, sc.up.Content, "/v1/help/articles/"+url.PathEscape(slug), nil, &existing) == nil {
					return "", domain.ValidationProblem(map[string]string{"slug": "A help page already uses this address"})
				}
				body, err := helpArticleBody(v, nil)
				if err != nil {
					return "", err
				}
				return slug, sc.call(ctx, sc.up.Content, port.StaffCall{Method: http.MethodPut, Path: "/v1/help/articles/" + url.PathEscape(slug), Body: body}, nil)
			}},
	}
}
