package httpapi

import (
	"errors"
	"html/template"
	"net/http"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/problem"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

// PermStockAlertGuest is the service scope the gateway-bff holds to subscribe a visitor who is not signed in.
const PermStockAlertGuest = "notifications:stock-alerts.guest"

// subscribeGuestStockAlert arms a back-in-stock alert for an email address. Only a service holding
// PermStockAlertGuest may call it (the BFF, which rate-limits and validates first). The answer is the same 201
// whether the address is new, already subscribed or at its cap, and never echoes the address.
func (h *handlersImpl) subscribeGuestStockAlert(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Atin         string `json:"atin"`
		Email        string `json:"email"`
		Market       string `json:"market"`
		Locale       string `json:"locale"`
		ProductTitle string `json:"productTitle"`
	}
	if !decode(w, r, &body) {
		return
	}
	atin, _ := domain.NormalizeAtin(body.Atin)
	err := h.d.StockAlerts.SubscribeGuest(r.Context(), domain.StockAlert{Atin: body.Atin, Email: body.Email,
		Market: body.Market, Locale: body.Locale, ProductTitle: body.ProductTitle})
	if err != nil {
		h.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]string{"atin": atin, "status": "subscribed"})
}

// ---- unsubscribe link of a back-in-stock email ----

type alertPageText struct {
	Title, Question, Button, Done, Invalid string
}

// alertPageTexts is the unsubscribe page in the storefront's locales (the link carries no locale, so the browser's
// Accept-Language decides).
var alertPageTexts = map[string]alertPageText{
	"en": {"Back-in-stock alert", "Stop this back-in-stock alert?", "Stop the alert",
		"Done. We won't email you about this item again. Nothing else about your account or emails changed.",
		"This link is invalid or has expired. If you still get emails about this item, reply to one of them and we'll stop it."},
	"fr": {"Alerte de retour en stock", "Arrêter cette alerte de retour en stock ?", "Arrêter l'alerte",
		"C'est fait. Nous ne vous écrirons plus au sujet de cet article. Rien d'autre n'a changé.",
		"Ce lien n'est pas valide ou a expiré. Si vous recevez encore des e-mails à propos de cet article, répondez à l'un d'eux et nous les arrêterons."},
	"es": {"Aviso de reposición", "¿Dejar de recibir este aviso de reposición?", "Detener el aviso",
		"Listo. No volveremos a escribirte sobre este artículo. No ha cambiado nada más.",
		"Este enlace no es válido o ha caducado. Si sigues recibiendo correos sobre este artículo, responde a uno de ellos y lo detendremos."},
	"ar": {"تنبيه توفر المنتج", "هل تريد إيقاف تنبيه توفر هذا المنتج؟", "إيقاف التنبيه",
		"تم. لن نراسلك بشأن هذا المنتج مرة أخرى. لم يتغير أي شيء آخر.",
		"هذا الرابط غير صالح أو انتهت صلاحيته. إذا استمررت في تلقي رسائل عن هذا المنتج، فردّ على إحداها وسنوقفها."},
	"yo": {"Ìkìlọ̀ ọjà tó padà dé", "Ṣé kí a dáwọ́ ìkìlọ̀ ọjà tó padà dé yìí dúró?", "Dá ìkìlọ̀ dúró",
		"Ó ti parí. A kò ní kọ̀wé sí ọ nípa ọjà yìí mọ́. Kò sí nǹkan mìíràn tó yípadà.",
		"Ìjápọ̀ yìí kò tọ̀nà tàbí ó ti parí. Tí o bá ṣì ń gba ímeèlì nípa ọjà yìí, dáhùn sí ọ̀kan nínú wọn a ó sì dá a dúró."},
}

var alertPageTpl = template.Must(template.New("alert").Parse(`<!doctype html>
<html lang="{{.Lang}}" dir="{{.Dir}}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><meta name="referrer" content="no-referrer">
<title>{{.T.Title}}</title>
<style>body{font-family:Figtree,Arial,sans-serif;background:#fff;color:#111;max-width:480px;margin:64px auto;padding:0 16px}
button{font:inherit;padding:10px 18px;border-radius:8px;border:1px solid #111;background:#111;color:#fff;cursor:pointer}</style>
</head><body>
{{if eq .State "done"}}<h1>{{.T.Title}}</h1><p>{{.T.Done}}</p>
{{else if eq .State "invalid"}}<h1>{{.T.Title}}</h1><p>{{.T.Invalid}}</p>
{{else}}<h1>{{.T.Question}}</h1>
<form method="post"><input type="hidden" name="List-Unsubscribe" value="One-Click"><button type="submit">{{.T.Button}}</button></form>{{end}}
</body></html>`))

type alertPage struct {
	Lang, Dir, State string
	T                alertPageText
}

// pickAlertLang chooses the page language from Accept-Language: the first listed tag whose primary subtag is supported.
func pickAlertLang(header string) string {
	for part := range strings.SplitSeq(header, ",") {
		tag, _, _ := strings.Cut(strings.TrimSpace(part), ";")
		lang, _, _ := strings.Cut(strings.ToLower(tag), "-")
		if _, ok := alertPageTexts[lang]; ok {
			return lang
		}
	}
	return "en"
}

func writeAlertPage(w http.ResponseWriter, r *http.Request, status int, state string) {
	lang := pickAlertLang(r.Header.Get("Accept-Language"))
	dir := "ltr"
	if lang == "ar" {
		dir = "rtl"
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
	w.WriteHeader(status)
	_ = alertPageTpl.Execute(w, alertPage{Lang: lang, Dir: dir, State: state, T: alertPageTexts[lang]})
}

func wantsHTML(r *http.Request) bool { return strings.Contains(r.Header.Get("Accept"), "text/html") }

// stockAlertUnsubscribePage asks for confirmation; GET never removes anything because link scanners prefetch URLs.
func (h *handlersImpl) stockAlertUnsubscribePage(w http.ResponseWriter, r *http.Request) {
	if _, err := h.d.Unsubscriber.VerifyStockAlert(r.URL.Query().Get("t")); err != nil {
		writeAlertPage(w, r, http.StatusBadRequest, "invalid")
		return
	}
	writeAlertPage(w, r, http.StatusOK, "confirm")
}

// stockAlertUnsubscribe removes the alert the signed token names: the RFC 8058 one-click POST of mail clients and
// the page's form. An alert that is already gone is still a success.
func (h *handlersImpl) stockAlertUnsubscribe(w http.ResponseWriter, r *http.Request) {
	err := h.d.StockAlerts.UnsubscribeByToken(r.Context(), r.URL.Query().Get("t"))
	switch {
	case errors.Is(err, app.ErrInvalidToken):
		if wantsHTML(r) {
			writeAlertPage(w, r, http.StatusBadRequest, "invalid")
			return
		}
		problem.Write(w, r, problem.New(http.StatusBadRequest, "notifications.invalid-token", "Unsubscribe link is invalid or expired"))
	case err != nil:
		h.writeErr(w, r, err)
	case wantsHTML(r):
		writeAlertPage(w, r, http.StatusOK, "done")
	default:
		w.WriteHeader(http.StatusOK)
	}
}
