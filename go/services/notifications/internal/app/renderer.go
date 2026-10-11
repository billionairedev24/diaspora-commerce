package app

import (
	"bytes"
	"errors"
	"fmt"
	htmltemplate "html/template"
	"strings"
	texttemplate "text/template"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// Renderer renders a template version with data. Missing fields are errors, so a template never goes out with
// "<no value>" in it.
//
// Email bodies are content fragments rendered inside the shared AfroTransact layout (email/layout.html.tmpl: black
// header with the logo, gold buttons, advertorials below the content, the WhatsApp community block and the footer
// with socials, app links, phone, address, preferences, unsubscribe and legal links). A body that is a complete HTML
// document (IsFullDocument) is sent as-is.
type Renderer interface {
	Render(t domain.Template, data map[string]any) (domain.Rendered, error)
	// Parse checks that a template compiles (used before storing a new version).
	Parse(t domain.Template) error
}

type rendererImpl struct{ layout *layoutBuilder }

var _ Renderer = (*rendererImpl)(nil)

// RendererOption configures the renderer.
type RendererOption func(*rendererOptions)

type rendererOptions struct {
	brand port.BrandSource
	now   func() time.Time
}

// WithBrand sets the brand (footer socials, WhatsApp community, phone, address, legal links) emails render with.
func WithBrand(b port.BrandSource) RendererOption { return func(o *rendererOptions) { o.brand = b } }

// WithClock fixes the clock (the footer's copyright year); tests and golden files use it.
func WithClock(now func() time.Time) RendererOption { return func(o *rendererOptions) { o.now = now } }

// NewRenderer returns the Go template renderer (html/template for email HTML, text/template elsewhere).
func NewRenderer(opts ...RendererOption) Renderer {
	o := rendererOptions{now: time.Now}
	for _, opt := range opts {
		opt(&o)
	}
	return &rendererImpl{layout: newLayoutBuilder(o.brand, o.now)}
}

// ErrRender is returned when a template cannot be parsed or executed with the given data.
var ErrRender = errors.New("template render failed")

func (r *rendererImpl) Render(t domain.Template, data map[string]any) (domain.Rendered, error) {
	out := domain.Rendered{Version: t.Version, Category: t.Category}
	clean, _, _ := splitReserved(data)
	var err error
	if out.Subject, err = renderText("subject", t.Subject, clean); err != nil {
		return out, err
	}
	out.Subject = strings.TrimSpace(out.Subject)
	if out.Text, err = renderText("text", t.BodyText, clean); err != nil {
		return out, err
	}
	if t.BodyHTML == "" {
		return out, nil
	}
	if IsFullDocument(t.BodyHTML) {
		tpl, perr := htmltemplate.New("html").Option("missingkey=error").Parse(t.BodyHTML)
		if perr != nil {
			return out, fmt.Errorf("%w: html: %w", ErrRender, perr)
		}
		var b bytes.Buffer
		if err := tpl.Execute(&b, clean); err != nil {
			return out, fmt.Errorf("%w: html: %w", ErrRender, err)
		}
		out.HTML = b.String()
		return out, nil
	}
	html, err := r.layout.render(t, out.Subject, data)
	if err != nil {
		return out, fmt.Errorf("%w: html: %w", ErrRender, err)
	}
	out.HTML = html
	if t.Channel == domain.ChannelEmail || t.Channel == "" {
		out.Text = strings.TrimRight(out.Text, "\n") + r.layout.textFooter(t, data)
	}
	return out, nil
}

func renderText(name, src string, data map[string]any) (string, error) {
	if src == "" {
		return "", nil
	}
	tpl, err := texttemplate.New(name).Option("missingkey=error").Parse(src)
	if err != nil {
		return "", fmt.Errorf("%w: %s: %w", ErrRender, name, err)
	}
	var b bytes.Buffer
	if err := tpl.Execute(&b, data); err != nil {
		return "", fmt.Errorf("%w: %s: %w", ErrRender, name, err)
	}
	return b.String(), nil
}

// Parse checks that every part of a template compiles (email fragments together with the layout's components).
func (r *rendererImpl) Parse(t domain.Template) error {
	for name, src := range map[string]string{"subject": t.Subject, "text": t.BodyText} {
		if _, err := texttemplate.New(name).Parse(src); err != nil {
			return fmt.Errorf("%w: %s: %w", ErrRender, name, err)
		}
	}
	if IsFullDocument(t.BodyHTML) {
		if _, err := htmltemplate.New("html").Parse(t.BodyHTML); err != nil {
			return fmt.Errorf("%w: html: %w", ErrRender, err)
		}
		return nil
	}
	if t.BodyHTML != "" {
		if _, err := r.layout.parse(t.BodyHTML, r.layout.currentBrand()); err != nil {
			return fmt.Errorf("%w: html: %w", ErrRender, err)
		}
	}
	return nil
}

// staticBrandImpl is a BrandSource that always returns the same snapshot.
type staticBrandImpl struct{ b domain.Brand }

var _ port.BrandSource = staticBrandImpl{}

// StaticBrand returns a BrandSource for a fixed brand (tests, the template gallery, config-only setups).
func StaticBrand(b domain.Brand) port.BrandSource { return staticBrandImpl{b: b} }

func (s staticBrandImpl) Brand() domain.Brand { return s.b }
