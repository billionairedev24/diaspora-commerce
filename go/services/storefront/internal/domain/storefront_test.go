package domain_test

import (
	"errors"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/storefront/internal/domain"
)

var limits = domain.Limits{MaxSections: 8, MaxGallery: 6, MaxFeatured: 6, MaxTaglineLength: 120}

func provider() domain.Storefront {
	return domain.Storefront{OrgID: "prov_ama", Kind: domain.KindProvider, Slug: "amas-braiding", Name: "Ama's Braiding Studio",
		Sections: []domain.Section{{Type: "hero", Enabled: true}, {Type: "gallery", Enabled: true}},
		Provider: &domain.ProviderDesign{Layout: "GALLERY_FIRST", Header: "BAND", ButtonShape: "ROUNDED", GallerySize: 4}}
}

func TestProviderValidation(t *testing.T) {
	s := provider()
	if err := s.Validate(limits); err != nil {
		t.Fatal(err)
	}
	s.Provider.GallerySize = 12
	s.Sections = append(s.Sections, domain.Section{Type: "featured_products"})
	s.Social = []domain.SocialLink{{Network: "instagram", URL: "https://evil.example.com/ama"}}
	err := s.Validate(limits)
	if !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("expected invalid, got %v", err)
	}
}

func TestSocialLinksStayApprovedOnlyWhenUnchanged(t *testing.T) {
	prev := []domain.SocialLink{{Network: "instagram", URL: "https://www.instagram.com/ama", Status: domain.LinkApproved}}
	next := domain.MergeSocial(prev, []domain.SocialLink{
		{Network: "instagram", URL: "https://www.instagram.com/ama"},
		{Network: "tiktok", URL: "https://www.tiktok.com/@ama"},
	})
	if next[0].Status != domain.LinkApproved || next[1].Status != domain.LinkPending {
		t.Fatalf("statuses %+v", next)
	}
}

func TestPublicViewHidesUnapprovedContent(t *testing.T) {
	s := provider()
	s.Gallery = make([]domain.GalleryItem, 6)
	s.Social = []domain.SocialLink{{Network: "instagram", URL: "https://www.instagram.com/a", Status: domain.LinkApproved},
		{Network: "tiktok", URL: "https://www.tiktok.com/@a", Status: domain.LinkPending}}
	s.Featured = []domain.FeaturedPost{{ID: "1", Headline: "Knotless", Published: true}, {ID: "2", Headline: "Draft", Published: false}}
	v := s.PublicView()
	if len(v.Gallery) != 4 || len(v.Social) != 1 || len(v.Featured) != 1 {
		t.Fatalf("public view %+v", v)
	}
}
