package app

import (
	"context"
	"fmt"
	"sort"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// GalleryEntry is one stored template (latest version of a key, locale and channel) rendered with its key's sample
// data. Err is set, and the rendered parts are empty, when the template does not render.
type GalleryEntry struct {
	Template domain.Template
	Sample   TemplateSample
	Rendered domain.Rendered
	// SMS is the encoding and segment count of the rendered text (sms and chat channels).
	SMS *domain.SmsSegments
	Err string
}

// TemplateGallery renders every stored template with its sample data, for the template gallery dev tool
// (cmd/template-gallery) and the tests that guard template edits.
type TemplateGallery interface {
	Entries(ctx context.Context) ([]GalleryEntry, error)
}

type templateGalleryImpl struct {
	store    port.TemplateStore
	renderer Renderer
	samples  TemplateSamples
}

var _ TemplateGallery = (*templateGalleryImpl)(nil)

// NewTemplateGallery returns the gallery use case over the template store, the real renderer and the samples.
func NewTemplateGallery(store port.TemplateStore, renderer Renderer, samples TemplateSamples) TemplateGallery {
	return &templateGalleryImpl{store: store, renderer: renderer, samples: samples}
}

// Entries returns the latest version of every key, locale and channel, sorted by area, key, locale and channel. A
// template that fails to render is returned with its error, never skipped.
func (g *templateGalleryImpl) Entries(ctx context.Context) ([]GalleryEntry, error) {
	tpls, err := g.store.List(ctx, "")
	if err != nil {
		return nil, fmt.Errorf("gallery: %w", err)
	}
	out := make([]GalleryEntry, 0, len(tpls))
	for _, t := range tpls {
		s, ok := g.samples.Sample(t.Key)
		e := GalleryEntry{Template: t, Sample: s}
		r, err := g.renderer.Render(t, s.Data)
		switch {
		case err != nil && !ok:
			e.Err = "no sample data for this key: " + err.Error()
		case err != nil:
			e.Err = err.Error()
		default:
			e.Rendered = r
			if t.Channel == domain.ChannelSMS || t.Channel == domain.ChannelChat {
				seg := domain.CountSmsSegments(r.Text)
				e.SMS = &seg
			}
		}
		out = append(out, e)
	}
	order := map[string]int{}
	for i, a := range Areas {
		order[a] = i
	}
	sort.SliceStable(out, func(i, j int) bool {
		a, b := out[i], out[j]
		if order[a.Sample.Area] != order[b.Sample.Area] {
			return order[a.Sample.Area] < order[b.Sample.Area]
		}
		if a.Template.Key != b.Template.Key {
			return a.Template.Key < b.Template.Key
		}
		if a.Template.Locale != b.Template.Locale {
			return a.Template.Locale < b.Template.Locale
		}
		return channelOrder(a.Template.Channel) < channelOrder(b.Template.Channel)
	})
	return out, nil
}

// Areas lists the template areas in display order.
var Areas = []string{AreaAccount, AreaOrders, AreaSeller, AreaPayouts, AreaBookings, AreaTrust, AreaRetail, AreaMarketing,
	AreaSecurity, AreaOther}

func channelOrder(c domain.Channel) int {
	switch c {
	case domain.ChannelEmail:
		return 0
	case domain.ChannelSMS:
		return 1
	case domain.ChannelPush:
		return 2
	default:
		return 3
	}
}
