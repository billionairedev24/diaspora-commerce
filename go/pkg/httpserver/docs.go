package httpserver

import (
	"fmt"
	"html"
	"net/http"
)

// Pinned API reference renderers (same versions as the Java services).
const (
	scalarVersion = "1.73.1"
	redocVersion  = "2.5.4"
)

// DocsPage renders an API reference page for the service's OpenAPI document.
type DocsPage interface {
	Path() string
	Render(title, specURL string) string
}

type scalarPage struct{}

type redocPage struct{}

var (
	_ DocsPage = scalarPage{}
	_ DocsPage = redocPage{}
)

// NewScalarPage serves Scalar at /docs.
func NewScalarPage() DocsPage { return scalarPage{} }

// NewRedocPage serves Redoc at /redoc.
func NewRedocPage() DocsPage { return redocPage{} }

func (scalarPage) Path() string { return "/docs" }

func (scalarPage) Render(title, specURL string) string {
	return fmt.Sprintf(`<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>%s · API reference</title></head>
<body><script id="api-reference" data-url="%s"></script>
<script src="https://cdn.jsdelivr.net/npm/@scalar/api-reference@%s"></script></body></html>`,
		html.EscapeString(title), html.EscapeString(specURL), scalarVersion)
}

func (redocPage) Path() string { return "/redoc" }

func (redocPage) Render(title, specURL string) string {
	return fmt.Sprintf(`<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>%s · Redoc</title><style>body{margin:0}</style></head>
<body><redoc spec-url="%s" hide-hostname="true"></redoc>
<script src="https://cdn.jsdelivr.net/npm/redoc@%s/bundles/redoc.standalone.js"></script></body></html>`,
		html.EscapeString(title), html.EscapeString(specURL), redocVersion)
}

func docsHandler(page DocsPage, title string) http.HandlerFunc {
	body := page.Render(title, "/openapi.yaml")
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write([]byte(body))
	}
}
