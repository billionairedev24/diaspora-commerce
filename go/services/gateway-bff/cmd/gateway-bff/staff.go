package main

import (
	"fmt"
	"log/slog"
	"net/http"

	"github.com/redis/go-redis/v9"

	gatewaybff "github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/exportfiles"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/upstreams"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/valkey"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// staffClients are the upstream clients the staff console shares with the rest of the BFF (one breaker per upstream);
// the remaining ones are created here.
type staffClients struct {
	identity, catalog, inventory, payments, orders, deliveryRuns, offers httpclient.Client
}

// staffServices wires the staff console (contracts/openapi/gateway-bff-staff.yaml): shell, tables and sheets,
// dashboard, exports, the support console, the fee-schedule editor, the seller pickups of dispatch and the
// notification templates, and the Inventory hub with AfroTransact Retail (1P) (S281).
// It also returns Seller Center's export stores (same job store, renderer and file directory; seller links are served by
// /v1/seller/export-files/{id}).
func staffServices(cfg config.Config, client func(string, config.Upstream) httpclient.Client, sc staffClients, rdb redis.UniversalClient,
	cache port.Cache, log *slog.Logger) (httpapi.StaffServices, app.ExportDeps, port.LocalExportFiles, error) {
	up := cfg.Upstreams
	media := client("media", up.Media)
	d := app.StaffDeps{
		Up: port.StaffUpstreams{
			Identity: upstreams.NewStaffUpstream(sc.identity), Catalog: upstreams.NewStaffUpstream(sc.catalog),
			Inventory: upstreams.NewStaffUpstream(sc.inventory), Payments: upstreams.NewStaffUpstream(sc.payments),
			Orders: upstreams.NewStaffUpstream(sc.orders), Seller: upstreams.NewStaffUpstream(client("seller", up.Seller)),
			TrustOps: upstreams.NewStaffUpstream(client("trust-ops", up.TrustOps)), TaxReturns: upstreams.NewStaffUpstream(client("tax-returns", up.TaxReturns)),
			Retail: upstreams.NewStaffUpstream(client("retail", up.Retail)), Fulfilment: upstreams.NewStaffUpstream(client("fulfilment", up.Fulfilment)),
			Content: upstreams.NewStaffUpstream(client("content", up.Content)), Notifications: upstreams.NewStaffUpstream(client("notifications", up.Notifications)),
			Bookings: upstreams.NewStaffUpstream(client("bookings", up.Bookings)), DeliveryRuns: upstreams.NewStaffUpstream(sc.deliveryRuns),
			Media: upstreams.NewStaffUpstream(media), Geo: upstreams.NewStaffUpstream(client("geo", up.Geo)),
			Offers: upstreams.NewStaffUpstream(sc.offers), Search: upstreams.NewStaffUpstream(client("search", up.Search)),
		},
		Matrix: upstreams.NewOrgAccess(sc.identity), Cache: cache, MatrixTTL: cfg.Cache.DefaultTTL,
		Jobs: valkey.NewExportJobs(rdb), Renderer: exportfiles.NewRenderer(), ExportBasePath: cfg.Web.BFFPath, Log: log, RetailOrgID: cfg.Commerce.RetailOrgID,
	}
	out := httpapi.StaffServices{}
	seller := app.ExportDeps{Jobs: d.Jobs, Renderer: d.Renderer, BasePath: cfg.Web.BFFPath}
	var sellerFiles port.LocalExportFiles
	switch cfg.Staff.ExportStore {
	case "media":
		d.Blobs = upstreams.NewMediaExports(media, nil)
		seller.Blobs = d.Blobs
	case "", "local":
		files, err := exportfiles.NewLocal(exportfiles.Options{Dir: cfg.Staff.ExportDir, SigningKey: cfg.Session.SigningKey, BasePath: cfg.Web.BFFPath})
		if err != nil {
			return out, seller, nil, fmt.Errorf("staff export store: %w", err)
		}
		sf, err := exportfiles.NewLocal(exportfiles.Options{Dir: cfg.Staff.ExportDir, SigningKey: cfg.Session.SigningKey, BasePath: cfg.Web.BFFPath,
			FilesPath: "/v1/seller/export-files/"})
		if err != nil {
			return out, seller, nil, fmt.Errorf("seller export store: %w", err)
		}
		d.Blobs, out.Files = files, files
		seller.Blobs, sellerFiles = sf, sf
	default:
		return out, seller, nil, fmt.Errorf("staff export store %q: use local or media", cfg.Staff.ExportStore)
	}
	out.Shell, out.Records, out.Dashboard = app.NewStaffShellService(d), app.NewStaffRecordsService(d), app.NewStaffDashboardService(d)
	out.Exports, out.Support, out.Fees = app.NewStaffExportsService(d), app.NewStaffSupportService(d), app.NewStaffFeesService(d)
	out.Dispatch, out.Notifications = app.NewStaffDispatchService(d), app.NewStaffNotificationsService(d)
	out.Preorders = app.NewStaffPreordersService(d)
	out.Inventory = app.NewStaffInventoryService(d)
	return out, seller, sellerFiles, nil
}

// serveStaffSpec publishes the staff contract next to /openapi.yaml.
func serveStaffSpec(mux *http.ServeMux) error {
	spec, err := gatewaybff.Files.ReadFile("api/openapi-staff.yaml")
	if err != nil {
		return fmt.Errorf("read staff openapi: %w", err)
	}
	mux.HandleFunc("GET /openapi-staff.yaml", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/yaml")
		_, _ = w.Write(spec)
	})
	return nil
}
