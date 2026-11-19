package app

import (
	"context"
	"net/http"
	"slices"
	"strings"
	"unicode/utf8"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/picturerules"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// StaffPictureRulesService is Settings > Picture rules (contracts/openapi/gateway-bff-staff.yaml, tag Picture rules): the ONE
// picture-rules setting (profile strict, startup or testing plus knobs) kept and audited by catalog-service. The BFF relays reads
// and changes with the staff member's own token, names the people (never ids), checks the shape and the reason, and decides
// nothing else: catalog checks the permission, the bounds and the version again.
type StaffPictureRulesService interface {
	// Get needs catalog:moderate or catalog:picture-rules.manage: the rules in force, who saved them, the profiles to compare, the
	// audit trail and whether the caller may change them. The answer's "version" is the ETag to send back as If-Match.
	Get(ctx context.Context, c *domain.Caller) (map[string]any, error)
	// Set needs catalog:picture-rules.manage: {profile, overrides, reason} with the version loaded as If-Match (428 without it).
	Set(ctx context.Context, c *domain.Caller, ifMatch string, body map[string]any) (map[string]any, error)
}

const (
	pictureRulesManage = "catalog:picture-rules.manage"
	pictureRulesView   = "catalog:moderate"
	minPictureReason   = 3
	maxPictureReason   = 300
)

type staffPictureRulesImpl struct{ r *staffResolver }

var _ StaffPictureRulesService = (*staffPictureRulesImpl)(nil)

// NewStaffPictureRulesService returns the Picture rules use case.
func NewStaffPictureRulesService(d StaffDeps) StaffPictureRulesService {
	return &staffPictureRulesImpl{r: newStaffResolver(d)}
}

func (s *staffPictureRulesImpl) Get(ctx context.Context, c *domain.Caller) (map[string]any, error) {
	sc, err := s.r.resolve(ctx, c)
	if err != nil {
		return nil, err
	}
	if !sc.access.Can(pictureRulesView) && !sc.access.Can(pictureRulesManage) {
		return nil, needsPermission(pictureRulesView)
	}
	sc.nameWho = true
	var res any
	if err := sc.get(ctx, sc.up.Catalog, "/v1/catalog/picture-rules", nil, &res); err != nil {
		return nil, err
	}
	m, _ := res.(jobj)
	return m, nil
}

func (s *staffPictureRulesImpl) Set(ctx context.Context, c *domain.Caller, ifMatch string, body map[string]any) (map[string]any, error) {
	sc, err := s.r.resolve(ctx, c)
	if err != nil {
		return nil, err
	}
	if !sc.access.Can(pictureRulesManage) {
		return nil, needsPermission(pictureRulesManage)
	}
	profile, overrides, problems := checkPictureRulesBody(body)
	if len(problems) > 0 {
		return nil, domain.ValidationProblem(problems)
	}
	if strings.TrimSpace(ifMatch) == "" {
		return nil, domain.NewProblem(http.StatusPreconditionRequired, "VERSION_REQUIRED", "Reload first",
			"Reload the page so the change is made against the rules you are looking at.")
	}
	reason, _ := body["reason"].(string)
	sc.nameWho = true
	var res any
	if err := sc.call(ctx, sc.up.Catalog, port.StaffCall{Method: http.MethodPut, Path: "/v1/catalog/picture-rules", IfMatch: ifMatch,
		Body: jobj{"profile": profile, "overrides": overrides, "reason": strings.TrimSpace(reason)}}, &res); err != nil {
		return nil, err
	}
	invalidatePictureRules()
	m, _ := res.(jobj)
	return m, nil
}

var pictureIntKnobs = []string{"minImages", "minLongSidePx", "minShortSidePx", "maxMegabytes"}
var pictureBoolKnobs = []string{"checkBackground", "checkAspect", "checkTextOverlay", "requireRealPhotoForStorefront", "requirePhotoForMatching", "allowPlaceholders"}

// checkPictureRulesBody checks the shape of a change: a known profile, only known knobs with the right type, and a reason of 3 to
// 300 characters (it is logged with the person's name). Bounds are catalog's to decide.
func checkPictureRulesBody(body map[string]any) (string, map[string]any, map[string]string) {
	problems := map[string]string{}
	profile, _ := body["profile"].(string)
	if _, ok := picturerules.ParseProfile(profile); !ok {
		problems["profile"] = "Choose strict, startup or testing"
	}
	overrides := map[string]any{}
	raw, _ := body["overrides"].(map[string]any)
	for k, v := range raw {
		switch {
		case v == nil:
			// null means "no override".
		case slices.Contains(pictureIntKnobs, k):
			f, ok := v.(float64)
			if !ok || f != float64(int64(f)) {
				problems[k] = "Enter a whole number"
				continue
			}
			overrides[k] = int64(f)
		case slices.Contains(pictureBoolKnobs, k):
			b, ok := v.(bool)
			if !ok {
				problems[k] = "Choose on or off"
				continue
			}
			overrides[k] = b
		default:
			problems[k] = "Not a picture rule"
		}
	}
	reason, _ := body["reason"].(string)
	if n := utf8.RuneCountInString(strings.TrimSpace(reason)); n < minPictureReason || n > maxPictureReason {
		problems["reason"] = "Say why, 3 to 300 characters: it is logged with your name"
	}
	return strings.ToLower(strings.TrimSpace(profile)), overrides, problems
}
