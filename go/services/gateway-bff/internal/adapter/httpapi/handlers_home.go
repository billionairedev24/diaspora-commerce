package httpapi

import (
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Cache headers of the dynamic home (docs/engineering/seo.md decision 6 and dynamic-home.md): a visitor's page is never
// cached; a crawler's data is cached 60 s with 300 s stale-while-revalidate, with Vary on what changes it.
const (
	cacheVisitor = "private, no-store"
	cacheCrawler = "public, max-age=60, stale-while-revalidate=300"
	cacheNavData = "public, max-age=60, stale-while-revalidate=300"
)

var botMarks = []string{"googlebot", "bingbot", "duckduckbot", "baiduspider", "yandexbot", "slurp", "applebot", "facebookexternalhit", "linkedinbot", "twitterbot", "gptbot", "claudebot", "perplexitybot", "ccbot", "bytespider", "semrushbot", "ahrefsbot"}

// isCrawler is the Worker's X-Crawler header or a known bot user agent.
func isCrawler(r *http.Request) bool {
	if v := strings.ToLower(strings.TrimSpace(r.Header.Get("X-Crawler"))); v == "1" || v == "true" {
		return true
	}
	ua := strings.ToLower(r.UserAgent())
	for _, m := range botMarks {
		if strings.Contains(ua, m) {
			return true
		}
	}
	return false
}

func splitHeader(v string, max int) []string {
	if strings.TrimSpace(v) == "" {
		return nil
	}
	parts := strings.Split(v, ",")
	if len(parts) > max {
		parts = parts[:max]
	}
	return parts
}

func (h *handlersImpl) homeV2(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	c := callerFrom(r.Context())
	req := app.HomeRequest{Caller: c, Locale: firstNonBlank(q.Get("locale"), r.Header.Get("Accept-Language")),
		Visitor: r.Header.Get("X-Visitor-Id"), Window: r.Header.Get("X-Visit-Window"), Variant: r.Header.Get("X-Home-Variant"),
		Seen: splitHeader(r.Header.Get("X-Home-Seen"), 150), Exclude: splitHeader(r.Header.Get("X-Home-Exclude"), 60), Crawler: isCrawler(r)}
	// Debug and replay are for staff only; for everyone else the parameters are ignored (no field, no error).
	if (q.Get("debug") == "1" || q.Get("debug") == "true" || q.Get("seed") != "") && h.s.Staff.Home != nil && h.s.Staff.Home.CanView(r.Context(), c) {
		req.Debug = q.Get("debug") == "1" || q.Get("debug") == "true"
		req.Seed = q.Get("seed")
		req.Debug = req.Debug || req.Seed != ""
	}
	if req.Crawler {
		w.Header().Set("Cache-Control", cacheCrawler)
	} else {
		w.Header().Set("Cache-Control", cacheVisitor)
	}
	w.Header().Add("Vary", "X-Crawler, Accept-Language")
	v, err := h.s.Site.HomeFor(r.Context(), req)
	if req.Debug {
		w.Header().Set("Cache-Control", cacheVisitor)
	}
	h.reply(w, r, http.StatusOK, v, err)
}

func firstNonBlank(vals ...string) string {
	for _, v := range vals {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}

func (h *handlersImpl) homeEvent(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Module   string `json:"module"`
		Event    string `json:"event"`
		Position int    `json:"position"`
		Variant  string `json:"variant"`
		Target   string `json:"target"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	err := h.s.Site.RecordModuleEvent(r.Context(), domain.HomeModuleEvent{Module: b.Module, Event: b.Event, Position: b.Position, Variant: b.Variant, Target: b.Target,
		Locale: r.Header.Get("Accept-Language")})
	h.reply(w, r, http.StatusAccepted, nil, err)
}

func (h *handlersImpl) navData(w http.ResponseWriter) { w.Header().Set("Cache-Control", cacheNavData) }

func (h *handlersImpl) siteNavigation(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Site.Navigation(r.Context(), firstNonBlank(r.URL.Query().Get("locale"), r.Header.Get("Accept-Language")))
	if err == nil {
		h.navData(w)
	}
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) siteDepartments(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	items, at, err := h.s.Site.Departments(r.Context(), firstNonBlank(q.Get("locale"), r.Header.Get("Accept-Language")), q.Get("includeEmpty") == "true")
	if err == nil {
		h.navData(w)
	}
	h.reply(w, r, http.StatusOK, map[string]any{"departments": items, "generatedAt": at.UTC().Format(time.RFC3339)}, err)
}

func (h *handlersImpl) siteCommunities(w http.ResponseWriter, r *http.Request) {
	items, at, err := h.s.Site.Communities(r.Context(), firstNonBlank(r.URL.Query().Get("locale"), r.Header.Get("Accept-Language")))
	if err == nil {
		h.navData(w)
	}
	h.reply(w, r, http.StatusOK, map[string]any{"communities": items, "generatedAt": at.UTC().Format(time.RFC3339)}, err)
}

func (h *handlersImpl) siteCommunity(w http.ResponseWriter, r *http.Request) {
	c, canonical, err := h.s.Site.Community(r.Context(), r.PathValue("slug"), firstNonBlank(r.URL.Query().Get("locale"), r.Header.Get("Accept-Language")))
	if errors.Is(err, port.ErrNotFound) {
		err = domain.NewProblem(http.StatusNotFound, "community.not-visible", "Community not available",
			"This community has no products shoppers can see yet.")
	}
	if err == nil {
		h.navData(w)
	}
	h.reply(w, r, http.StatusOK, map[string]any{"community": c, "canonical": canonical}, err)
}

func (h *handlersImpl) siteSitemap(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Site.SitemapSources(r.Context())
	if err == nil {
		w.Header().Set("Cache-Control", "public, max-age=300")
	}
	h.reply(w, r, http.StatusOK, v, err)
}

// ------------------------------------------------------------------ staff: Marketing > Home

func (h *handlersImpl) registerStaffHome(route func(pattern string, fn http.HandlerFunc)) {
	route("GET /v1/staff/home/settings", h.staffHomeSettings)
	route("PUT /v1/staff/home/settings", h.staffHomeSaveSettings)
	route("GET /v1/staff/home/settings/versions", h.staffHomeVersions)
	route("POST /v1/staff/home/settings/revert", h.staffHomeRevert)
	route("GET /v1/staff/home/communities", h.staffHomeCommunities)
	route("PUT /v1/staff/home/communities/{key}", h.staffHomePutCommunity)
	route("DELETE /v1/staff/home/communities/{key}", h.staffHomeDeleteCommunity)
	route("GET /v1/staff/home/preview", h.staffHomePreview)
}

func (h *handlersImpl) staffHomeSettings(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Home.Settings(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffHomeSaveSettings(w http.ResponseWriter, r *http.Request) {
	var in app.StaffHomeSave
	if err := decode(r, &in, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Home.SaveSettings(r.Context(), callerFrom(r.Context()), in)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffHomeVersions(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Home.Versions(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffHomeRevert(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ToVersion int    `json:"toVersion"`
		Reason    string `json:"reason"`
	}
	if err := decode(r, &in, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Home.Revert(r.Context(), callerFrom(r.Context()), in.ToVersion, in.Reason)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffHomeCommunities(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Home.Communities(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffHomePutCommunity(w http.ResponseWriter, r *http.Request) {
	var in map[string]any
	if err := decode(r, &in, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Home.PutCommunity(r.Context(), callerFrom(r.Context()), r.PathValue("key"), in)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffHomeDeleteCommunity(w http.ResponseWriter, r *http.Request) {
	err := h.s.Staff.Home.DeleteCommunity(r.Context(), callerFrom(r.Context()), r.PathValue("key"), r.URL.Query().Get("reason"))
	h.reply(w, r, http.StatusNoContent, nil, err)
}

func (h *handlersImpl) staffHomePreview(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	in := app.StaffHomePreviewQuery{ZIP: q.Get("zip"), Locale: q.Get("locale"), Catalog: q.Get("catalog"), Seed: q.Get("seed"), Variant: q.Get("variant")}
	in.SignedIn, _ = strconv.ParseBool(q.Get("signedIn"))
	in.Deterministic, _ = strconv.ParseBool(q.Get("deterministic"))
	if v := q.Get("asOf"); v != "" {
		t, err := time.Parse(time.RFC3339, v)
		if err != nil {
			h.reply(w, r, 0, nil, domain.ValidationProblem(map[string]string{"asOf": "Use a date and time like 2026-12-01T09:00:00Z"}))
			return
		}
		in.AsOf = t
	}
	v, err := h.s.Staff.Home.Preview(r.Context(), callerFrom(r.Context()), in)
	h.reply(w, r, http.StatusOK, v, err)
}
