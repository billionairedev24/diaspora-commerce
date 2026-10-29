package app

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net/http"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// legalLocales are the storefront locales content may hold reviewed translations for.
var legalLocales = map[string]bool{"en": true, "fr": true, "es": true, "ar": true, "yo": true}

func legalNotFound() *domain.Problem {
	return domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "We couldn't find that document.")
}

// LegalIndex is content's index limited to the documents this BFF knows (so a newer content service never leaks a
// key the storefront cannot route), in the legal center's order.
func (s *siteServiceImpl) LegalIndex(ctx context.Context) (domain.LegalIndex, error) {
	idx, err := s.d.Content.LegalIndex(ctx)
	if err != nil {
		return domain.LegalIndex{Documents: []domain.LegalSummary{}}, mapErr("content", err)
	}
	byKey := make(map[string]domain.LegalSummary, len(idx.Documents))
	for _, d := range idx.Documents {
		byKey[d.Key] = d
	}
	out := domain.LegalIndex{Documents: make([]domain.LegalSummary, 0, len(byKey))}
	for _, k := range domain.LegalDocumentKeys {
		if d, ok := byKey[k]; ok {
			out.Documents = append(out.Documents, d)
		}
	}
	return out, nil
}

func (s *siteServiceImpl) LegalDocument(ctx context.Context, key, version, locale string) (domain.LegalPage, error) {
	if !domain.IsLegalDocument(key) {
		return domain.LegalPage{}, legalNotFound()
	}
	loc := strings.ToLower(strings.TrimSpace(locale))
	if !legalLocales[loc] {
		loc = "en"
	}
	p, err := s.d.Content.LegalDocument(ctx, key, strings.TrimSpace(version), loc)
	if err != nil {
		if errors.Is(err, port.ErrNotFound) {
			return p, legalNotFound().WithCause(err)
		}
		return p, mapErr("content", err)
	}
	p.Outline = orEmpty(p.Outline)
	p.Versions = orEmpty(p.Versions)
	return p, nil
}

func (s *siteServiceImpl) CookiePolicy(ctx context.Context) (domain.CookiePolicy, error) {
	p, err := s.d.Content.CookiePolicy(ctx)
	if err != nil {
		return p, mapErr("content", err)
	}
	p.Categories = orEmpty(p.Categories)
	p.Cookies = orEmpty(p.Cookies)
	return p, nil
}

// consentSubject identifies the decision without exposing the session id: the signed-in user, a hash of the
// session id for guests, or a one-off id when no session exists yet.
func consentSubject(c *domain.Caller) string {
	if c.Authenticated() {
		return "user:" + c.Subject
	}
	if c != nil && c.Session != nil && c.Session.ID != "" {
		sum := sha256.Sum256([]byte("consent:" + c.Session.ID))
		return "anon:" + hex.EncodeToString(sum[:12])
	}
	b := make([]byte, 12)
	_, _ = rand.Read(b)
	return "anon:" + hex.EncodeToString(b)
}

func (s *siteServiceImpl) RecordConsent(ctx context.Context, c *domain.Caller, in domain.ConsentChoice, secGPC bool) (domain.ConsentRecord, error) {
	src := strings.TrimSpace(in.Source)
	if src == "" {
		src = "banner"
	}
	if !domain.ConsentSources[src] {
		return domain.ConsentRecord{}, domain.ValidationProblem(map[string]string{"source": "One of banner, preferences, privacy-choices"})
	}
	gpc := in.GPC || secGPC
	r, err := s.d.Content.RecordConsent(ctx, port.ContentConsent{Subject: consentSubject(c), Categories: in.Categories,
		GPC: gpc, SaleOptOut: in.SaleOptOut || gpc, Source: src})
	if err != nil {
		return r, mapErr("content", err)
	}
	return r, nil
}

func (s *siteServiceImpl) SubmitPrivacyRequest(ctx context.Context, c *domain.Caller, in domain.PrivacyRequest) (domain.PrivacyRequestReceipt, error) {
	req, errs := domain.NormalisePrivacyRequest(in)
	if len(errs) > 0 {
		return domain.PrivacyRequestReceipt{}, domain.ValidationProblem(errs)
	}
	bearer := ""
	if c.Authenticated() {
		bearer = c.BearerToken
	}
	r, err := s.d.Content.SubmitPrivacyRequest(ctx, bearer, req)
	if err != nil {
		return r, mapErr("content", err)
	}
	return r, nil
}
