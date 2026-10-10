package app

import (
	"bytes"
	"errors"
	"fmt"
	htmltemplate "html/template"
	"strings"
	texttemplate "text/template"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

// Renderer renders a template version with data. Missing fields are errors, so a template never goes out with
// "<no value>" in it.
type Renderer interface {
	Render(t domain.Template, data map[string]any) (domain.Rendered, error)
	// Parse checks that a template compiles (used before storing a new version).
	Parse(t domain.Template) error
}

type rendererImpl struct{}

var _ Renderer = (*rendererImpl)(nil)

// NewRenderer returns the Go template renderer (html/template for email HTML, text/template elsewhere).
func NewRenderer() Renderer { return &rendererImpl{} }

// ErrRender is returned when a template cannot be parsed or executed with the given data.
var ErrRender = errors.New("template render failed")

func (rendererImpl) Render(t domain.Template, data map[string]any) (domain.Rendered, error) {
	out := domain.Rendered{Version: t.Version, Category: t.Category}
	var err error
	if out.Subject, err = renderText("subject", t.Subject, data); err != nil {
		return out, err
	}
	out.Subject = strings.TrimSpace(out.Subject)
	if out.Text, err = renderText("text", t.BodyText, data); err != nil {
		return out, err
	}
	if t.BodyHTML != "" {
		tpl, perr := htmltemplate.New("html").Option("missingkey=error").Parse(t.BodyHTML)
		if perr != nil {
			return out, fmt.Errorf("%w: html: %w", ErrRender, perr)
		}
		var b bytes.Buffer
		if err := tpl.Execute(&b, data); err != nil {
			return out, fmt.Errorf("%w: html: %w", ErrRender, err)
		}
		out.HTML = b.String()
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

// Parse checks that every part of a template compiles.
func (rendererImpl) Parse(t domain.Template) error {
	for name, src := range map[string]string{"subject": t.Subject, "text": t.BodyText} {
		if _, err := texttemplate.New(name).Parse(src); err != nil {
			return fmt.Errorf("%w: %s: %w", ErrRender, name, err)
		}
	}
	if _, err := htmltemplate.New("html").Parse(t.BodyHTML); err != nil {
		return fmt.Errorf("%w: html: %w", ErrRender, err)
	}
	return nil
}
