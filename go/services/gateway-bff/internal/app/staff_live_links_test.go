package app

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

func TestLiveLinksAreAbsoluteStorefrontAddresses(t *testing.T) {
	sc := staffCtx{storefront: "https://shop.example.test"}
	got := liveLinks(sc, []domain.RecordLink{
		{Label: "Open the live page", Href: "/legal/privacy", Storefront: true},
		{Label: "Customer", Href: "/organisations?open=o1"},
	})
	if len(got) != 2 {
		t.Fatalf("links = %+v", got)
	}
	if got[0].Href != "https://shop.example.test/legal/privacy" || !got[0].External {
		t.Errorf("storefront link = %+v, want an absolute external address", got[0])
	}
	if got[1].Href != "/organisations?open=o1" || got[1].External {
		t.Errorf("console link changed: %+v", got[1])
	}
}

func TestLiveLinksDroppedWithoutStorefrontOrigin(t *testing.T) {
	got := liveLinks(staffCtx{}, []domain.RecordLink{{Label: "Open", Href: "/help", Storefront: true}})
	if len(got) != 0 {
		t.Errorf("a storefront link without an origin must not point at the console: %+v", got)
	}
}

func TestResourceLinksUseTheStorefrontPath(t *testing.T) {
	help := helpArticlesResource().links(jobj{"status": "PUBLISHED", "slug": "shipping", "path": helpPath("shipping")})
	legal := legalResource().links(jobj{"key": "privacy", "path": "/legal/privacy"})
	for name, links := range map[string][]domain.RecordLink{"help": help, "legal": legal} {
		if len(links) != 2 || links[0].Storefront || !links[1].Storefront || links[1].Href[0] != '/' {
			t.Errorf("%s links = %+v", name, links)
		}
	}
	if legal[0].Href != "/settings/pages/legal/privacy" || help[0].Href != "/settings/pages/help/shipping" {
		t.Errorf("editor links = %+v %+v", legal[0], help[0])
	}
	hidden := helpArticlesResource().links(jobj{"status": "DRAFT", "slug": "wip", "path": helpPath("wip")})
	if len(hidden) != 1 || hidden[0].Storefront {
		t.Errorf("a hidden page has no live link: %+v", hidden)
	}
	if helpPath(domain.HelpHub) != "/help" || helpPath("shipping") != "/help/shipping" {
		t.Error("help paths")
	}
}
