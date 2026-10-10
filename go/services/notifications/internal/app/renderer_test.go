package app_test

import (
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

func verificationTemplate() domain.Template {
	return domain.Template{Key: "identity.verification", Locale: "en", Channel: domain.ChannelEmail, Version: 3,
		Category: domain.CategorySecurity, Subject: "Your code: {{.code}}",
		BodyHTML: "<p>Hi {{.name}}, your code is <b>{{.code}}</b></p>",
		BodyText: "Hi {{.name}}, your code is {{.code}}"}
}

func TestRenderEmail(t *testing.T) {
	r, err := app.NewRenderer().Render(verificationTemplate(), map[string]any{"code": "246810", "name": "<Ada>"})
	if err != nil {
		t.Fatal(err)
	}
	if r.Subject != "Your code: 246810" || r.Version != 3 || r.Category != domain.CategorySecurity {
		t.Fatalf("%+v", r)
	}
	if !strings.Contains(r.HTML, "Hi &lt;Ada&gt;") {
		t.Fatalf("html must be escaped: %s", r.HTML)
	}
	if r.Text != "Hi <Ada>, your code is 246810" {
		t.Fatalf("text must not be escaped: %s", r.Text)
	}
}

func TestRenderMissingFieldFails(t *testing.T) {
	_, err := app.NewRenderer().Render(verificationTemplate(), map[string]any{"name": "Ada"})
	if !errors.Is(err, app.ErrRender) {
		t.Fatalf("missing code must fail, got %v", err)
	}
}

func TestRenderCompiledReactEmailHTML(t *testing.T) {
	tpl := domain.Template{Channel: domain.ChannelEmail, Category: domain.CategoryTransactional, Subject: "x",
		BodyHTML: `<!DOCTYPE html><html><head><style>.a{color:#111}</style></head><body><table role="presentation"><tr>` +
			`<td><a href="{{.url}}">Track {{.orderNumber}}</a></td></tr></table></body></html>`, BodyText: "t"}
	r, err := app.NewRenderer().Render(tpl, map[string]any{"url": "https://afrotransact.com/o/1?a=b", "orderNumber": "AT-1"})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(r.HTML, `href="https://afrotransact.com/o/1?a=b"`) || !strings.Contains(r.HTML, ".a{color:#111}") {
		t.Fatal(r.HTML)
	}
	bad := tpl
	bad.BodyHTML = "{{.url"
	if err := app.NewRenderer().Parse(bad); !errors.Is(err, app.ErrRender) {
		t.Fatal("parse error expected")
	}
}

func TestUnsubscribeTokens(t *testing.T) {
	u := app.NewUnsubscriber("https://api.afrotransact.com/notifications", "unsubscribe@mail.afrotransact.com", []byte("k"))
	h := u.Headers("user-1", "Ada@Example.com", domain.CategoryMarketing, time.Now())
	if h["List-Unsubscribe-Post"] != "List-Unsubscribe=One-Click" {
		t.Fatal(h)
	}
	lu := h["List-Unsubscribe"]
	if !strings.HasPrefix(lu, "<https://api.afrotransact.com/notifications/v1/unsubscribe?t=") || !strings.Contains(lu, "<mailto:unsubscribe@") {
		t.Fatal(lu)
	}
	link := strings.TrimSuffix(strings.SplitN(lu, ">", 2)[0][1:], ">")
	tok := strings.SplitN(link, "t=", 2)[1]
	tok = strings.ReplaceAll(tok, "%3D", "=")
	c, err := u.Verify(tok)
	if err != nil || c.RecipientKey != "user-1" || c.Address != "ada@example.com" || c.Category != domain.CategoryMarketing {
		t.Fatalf("%+v %v", c, err)
	}
	if _, err := u.Verify(tok[:len(tok)-2] + "xx"); !errors.Is(err, app.ErrInvalidToken) {
		t.Fatal("tampered token accepted")
	}
	other := app.NewUnsubscriber("https://x", "", []byte("other-key"))
	if _, err := other.Verify(tok); !errors.Is(err, app.ErrInvalidToken) {
		t.Fatal("token signed with another key accepted")
	}
}
