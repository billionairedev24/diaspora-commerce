package app

import (
	"context"
	"slices"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// libraryStatuses are the image library statuses a seller can filter by.
var libraryStatuses = []string{"READY", "FLAGGED", "REJECTED"}

func (s *sellerImportImpl) ImageLibrary(ctx context.Context, c *domain.Caller, q, status, matched string, page, size int) (domain.ImageLibraryPage, error) {
	errs := map[string]string{}
	status = strings.ToUpper(strings.TrimSpace(status))
	if status != "" && !slices.Contains(libraryStatuses, status) {
		errs["status"] = "READY, FLAGGED or REJECTED"
	}
	if matched != "" && matched != "true" && matched != "false" {
		errs["matched"] = "true or false"
	}
	if size == 0 {
		size = 50
	}
	if page < 0 {
		errs["page"] = "Must be 0 or more"
	}
	if size < 1 || size > 200 {
		errs["size"] = "Between 1 and 200"
	}
	if len(q) > 120 {
		errs["q"] = "At most 120 characters"
	}
	if len(errs) > 0 {
		return domain.ImageLibraryPage{}, domain.ValidationProblem(errs)
	}
	if _, err := s.r.requireCap(ctx, c, domain.OrgKindSeller, "listings", domain.LevelView, domain.CapImageLibrary); err != nil {
		return domain.ImageLibraryPage{}, err
	}
	if s.r.d.Media == nil {
		return domain.ImageLibraryPage{}, domain.UpstreamUnavailable("media")
	}
	p, err := s.r.d.Media.Library(ctx, c.BearerToken, strings.TrimSpace(q), status, matched, page, size)
	if err != nil {
		return domain.ImageLibraryPage{}, mapErr("media", err)
	}
	out := domain.ImageLibraryPage{Items: make([]domain.ImageLibraryItem, 0, len(p.Items)), Total: p.Total, Page: p.Page, Size: p.Size}
	urls := []string{}
	skus := []string{}
	for _, i := range p.Items {
		if i.URL != nil && *i.URL != "" {
			urls = append(urls, *i.URL)
		}
		if i.SKU != nil && *i.SKU != "" && !slices.Contains(skus, *i.SKU) {
			skus = append(skus, *i.SKU)
		}
	}
	usedBy := map[string][]string{}
	known := map[string]bool{}
	if cl, cerr := s.client(); cerr == nil && (len(urls) > 0 || len(skus) > 0) {
		// "Used by" is best effort: the library still lists when catalog is unavailable.
		if u, uerr := cl.ImageUsage(ctx, c.BearerToken, urls, skus); uerr == nil {
			for _, x := range u.Usage {
				usedBy[x.URL] = x.SKUs
			}
			for _, k := range u.KnownSKUs {
				known[strings.ToUpper(k)] = true
			}
		}
	}
	for _, i := range p.Items {
		flags := i.Flags
		if flags == nil {
			flags = []string{}
		}
		item := domain.ImageLibraryItem{AssetID: i.AssetID, FileName: i.FileName, SKU: i.SKU, Slot: i.Slot, Status: strings.ToUpper(i.Status),
			URL: i.URL, Width: i.Width, Height: i.Height, Bytes: i.Bytes, Flags: flags, CreatedAt: i.CreatedAt, UsedBy: []string{}}
		if i.URL != nil {
			if u, ok := usedBy[*i.URL]; ok {
				item.UsedBy = u
			}
		}
		if i.SKU != nil {
			item.SKUKnown = known[strings.ToUpper(*i.SKU)]
		}
		out.Items = append(out.Items, item)
	}
	return out, nil
}

func (s *sellerImportImpl) RemoveLibraryImage(ctx context.Context, c *domain.Caller, assetID string) error {
	if strings.TrimSpace(assetID) == "" {
		return domain.ValidationProblem(map[string]string{"assetId": "Required"})
	}
	if _, err := s.r.requireCap(ctx, c, domain.OrgKindSeller, "listings", domain.LevelEdit, domain.CapImageLibrary); err != nil {
		return err
	}
	if s.r.d.Media == nil {
		return domain.UpstreamUnavailable("media")
	}
	if err := s.r.d.Media.RemoveFromLibrary(ctx, c.BearerToken, assetID); err != nil {
		return mapErr("media", err)
	}
	return nil
}

func (s *sellerImportImpl) RenameLibraryImage(ctx context.Context, c *domain.Caller, assetID, fileName string) (domain.ImageLibraryItem, error) {
	assetID = strings.TrimSpace(assetID)
	fileName = strings.TrimSpace(fileName)
	errs := map[string]string{}
	if assetID == "" {
		errs["assetId"] = "Required"
	}
	if fileName == "" {
		errs["fileName"] = "Required"
	}
	if len(errs) > 0 {
		return domain.ImageLibraryItem{}, domain.ValidationProblem(errs)
	}
	if _, err := s.r.requireCap(ctx, c, domain.OrgKindSeller, "listings", domain.LevelEdit, domain.CapImageLibrary); err != nil {
		return domain.ImageLibraryItem{}, err
	}
	if s.r.d.Media == nil {
		return domain.ImageLibraryItem{}, domain.UpstreamUnavailable("media")
	}
	i, err := s.r.d.Media.RenameLibraryImage(ctx, c.BearerToken, assetID, fileName)
	if err != nil {
		return domain.ImageLibraryItem{}, mapErr("media", err)
	}
	flags := i.Flags
	if flags == nil {
		flags = []string{}
	}
	return domain.ImageLibraryItem{
		AssetID: i.AssetID, FileName: i.FileName, SKU: i.SKU, Slot: i.Slot, Status: strings.ToUpper(i.Status),
		URL: i.URL, Width: i.Width, Height: i.Height, Bytes: i.Bytes, Flags: flags, CreatedAt: i.CreatedAt, UsedBy: []string{},
	}, nil
}

func (s *sellerImportImpl) AttachLibraryImages(ctx context.Context, c *domain.Caller, skus []string) (domain.AttachLibraryImagesResult, error) {
	if len(skus) > 5000 {
		return domain.AttachLibraryImagesResult{}, domain.ValidationProblem(map[string]string{"skus": "At most 5,000 SKUs"})
	}
	clean := make([]string, 0, len(skus))
	for _, k := range skus {
		if k = strings.TrimSpace(k); k != "" {
			if len(k) > 64 {
				return domain.AttachLibraryImagesResult{}, domain.ValidationProblem(map[string]string{"skus": "SKUs are at most 64 characters"})
			}
			clean = append(clean, k)
		}
	}
	if _, err := s.r.requireCap(ctx, c, domain.OrgKindSeller, "listings", domain.LevelEdit, domain.CapImageLibrary); err != nil {
		return domain.AttachLibraryImagesResult{}, err
	}
	cl, err := s.client()
	if err != nil {
		return domain.AttachLibraryImagesResult{}, err
	}
	res, err := cl.AttachLibraryImages(ctx, c.BearerToken, clean)
	if err != nil {
		return domain.AttachLibraryImagesResult{}, mapErr("catalog", err)
	}
	return res, nil
}

func (s *sellerImportImpl) Brands(ctx context.Context, c *domain.Caller, q string) (domain.BrandList, error) {
	if len(q) > 80 {
		return domain.BrandList{}, domain.ValidationProblem(map[string]string{"q": "At most 80 characters"})
	}
	if _, err := s.r.requireCap(ctx, c, domain.OrgKindSeller, "listings", domain.LevelView, domain.CapImageLibrary); err != nil {
		return domain.BrandList{}, err
	}
	cl, err := s.client()
	if err != nil {
		return domain.BrandList{}, err
	}
	b, err := cl.Brands(ctx, c.BearerToken, strings.TrimSpace(q))
	if err != nil {
		return domain.BrandList{}, mapErr("catalog", err)
	}
	return b, nil
}
