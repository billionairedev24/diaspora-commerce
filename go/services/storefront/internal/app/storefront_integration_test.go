package app_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/testdocker"
	"github.com/billionairedev24/diaspora-commerce/go/services/storefront"
	"github.com/billionairedev24/diaspora-commerce/go/services/storefront/internal/adapter/cdn"
	"github.com/billionairedev24/diaspora-commerce/go/services/storefront/internal/adapter/pgrepo"
	"github.com/billionairedev24/diaspora-commerce/go/services/storefront/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/storefront/internal/domain"
)

type limits struct{}

func (limits) For(string) domain.Limits {
	return domain.Limits{MaxSections: 10, MaxGallery: 6, MaxFeatured: 6, MaxTaglineLength: 120}
}

func TestDesignPublishModerateFlow(t *testing.T) {
	testdocker.Require(t)
	ctx := context.Background()
	ctr, err := testcontainers.Run(ctx, "postgis/postgis:17-3.5",
		testcontainers.WithEnv(map[string]string{"POSTGRES_USER": "s", "POSTGRES_PASSWORD": "s", "POSTGRES_DB": "s"}),
		testcontainers.WithExposedPorts("5432/tcp"),
		testcontainers.WithWaitStrategy(wait.ForLog("database system is ready to accept connections").
			WithOccurrence(2).WithStartupTimeout(2*time.Minute)))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ctr.Terminate(context.Background()) })
	hp, err := ctr.PortEndpoint(ctx, "5432/tcp", "")
	if err != nil {
		t.Fatal(err)
	}
	pool, err := pgxpool.New(ctx, "postgres://s:s@"+hp+"/s")
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	if err := pgrepo.Migrate(ctx, pool, storefront.Files); err != nil {
		t.Fatal(err)
	}
	svc := app.NewStorefrontService(pgrepo.New(pool), cdn.NewNoop(), limits{}, "https://www.afrotransact.com")
	owner := auth.Actor{Subject: "u1", Type: "PROVIDER_MEMBER", OrgID: "prov_ama", Permissions: []string{app.PermEdit}}
	staff := auth.Actor{Subject: "s1", Type: "STAFF", Permissions: []string{app.PermModerate}}

	d, err := svc.Draft(ctx, owner)
	if err != nil || d.Kind != domain.KindProvider || len(d.Sections) == 0 {
		t.Fatalf("starter draft %+v %v", d, err)
	}
	d.Slug, d.Name, d.Tagline = "amas-braiding", "Ama's Braiding Studio", "Knotless, cornrows and locs in Edison"
	d.Theme = domain.ThemeInput{Primary: "#7A2E8E", Accent: "#FFD400", Text: "#BBBBBB"} // body text too light
	d.Social = []domain.SocialLink{{Network: "instagram", URL: "https://www.instagram.com/amasbraids"}}
	d.Gallery = []domain.GalleryItem{{AssetID: "a1", URL: "https://cdn/1.jpg"}, {AssetID: "a2", URL: "https://cdn/2.jpg"}}
	d.Provider.GallerySize = 1
	if d, err = svc.SaveDraft(ctx, owner, d); err != nil {
		t.Fatal(err)
	}
	if _, vet, err := svc.Publish(ctx, owner); !errors.Is(err, domain.ErrThemeFails) || vet.OK {
		t.Fatalf("publish must be blocked by contrast, got %v", err)
	}
	d.Theme.Text = "#191816"
	if d, err = svc.SaveDraft(ctx, owner, d); err != nil {
		t.Fatal(err)
	}
	if _, _, err := svc.Publish(ctx, owner); err != nil {
		t.Fatal(err)
	}
	pub, err := svc.Public(ctx, "amas-braiding")
	if err != nil {
		t.Fatal(err)
	}
	if len(pub.Social) != 0 || len(pub.Gallery) != 1 || pub.Tokens["--store-on-accent"] != "#000000" {
		t.Fatalf("public view before approval %+v", pub)
	}
	pending, err := svc.PendingLinks(ctx, staff)
	if err != nil || len(pending) != 1 {
		t.Fatalf("pending links %v %v", pending, err)
	}
	if _, err := svc.DecideLink(ctx, staff, "prov_ama", "instagram", true); err != nil {
		t.Fatal(err)
	}
	pub, _ = svc.Public(ctx, "amas-braiding")
	if len(pub.Social) != 1 {
		t.Fatalf("approved link should be public: %+v", pub.Social)
	}
	if _, err := svc.PendingLinks(ctx, owner); !errors.Is(err, app.ErrForbidden) {
		t.Fatal("owners cannot moderate")
	}

	other := auth.Actor{Subject: "u2", Type: "SELLER_MEMBER", OrgID: "org_other", Permissions: []string{app.PermEdit}}
	od, _ := svc.Draft(ctx, other)
	od.Slug, od.Name = "amas-braiding", "Copycat"
	if _, err := svc.SaveDraft(ctx, other, od); !errors.Is(err, domain.ErrSlugTaken) {
		t.Fatalf("slug clash must be refused, got %v", err)
	}
}
