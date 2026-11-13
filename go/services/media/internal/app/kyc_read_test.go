package app_test

import (
	"context"
	"errors"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
)

func TestStaffNeedKYCReadToOpenKYCDocumentsAndProofPhotos(t *testing.T) {
	f := newDocuments(0, domain.LinkPolicy{})
	ctx := context.Background()
	kyc := f.upload(t, sellerMember, domain.PurposeKYCDocument, "ein.pdf", domain.ContentTypePDF, pdf())
	driver := auth.Actor{Subject: "drv-1", Type: "DRIVER", Permissions: []string{"delivery:operate"}}
	proof := f.upload(t, driver, domain.PurposeProofPhoto, "drop.jpg", "image/jpeg", productPhoto(t, 3))

	plainStaff := auth.Actor{Subject: "stf-2", Type: "STAFF", Permissions: []string{"media:moderate", "catalog:moderate"}}
	reviewer := auth.Actor{Subject: "stf-3", Type: "STAFF", Permissions: []string{domain.PermissionKYCRead}}
	superAdmin := auth.Actor{Subject: "stf-4", Type: "STAFF", Permissions: []string{"*"}}

	for _, id := range []string{kyc.ID, proof.ID} {
		for _, denied := range []auth.Actor{staff, plainStaff} {
			if _, err := f.svc.Get(ctx, denied, id, 0); !errors.Is(err, app.ErrForbidden) {
				t.Errorf("%s without kyc:read read %s: %v, want forbidden", denied.Subject, id, err)
			}
		}
		for _, allowed := range []auth.Actor{reviewer, superAdmin} {
			if _, err := f.svc.Get(ctx, allowed, id, 0); err != nil {
				t.Errorf("%s with kyc:read could not read %s: %v", allowed.Subject, id, err)
			}
		}
	}
	// Managing an asset the same way: staff need the permission to touch them.
	if _, err := f.svc.Complete(ctx, plainStaff, kyc.ID); !errors.Is(err, app.ErrForbidden) {
		t.Errorf("staff without kyc:read completing a KYC document: %v", err)
	}
	// The uploader and the owner organisation are unaffected, and other purposes need no kyc:read.
	if _, err := f.svc.Get(ctx, sellerMember, kyc.ID, 0); err != nil {
		t.Errorf("owner read its own KYC document: %v", err)
	}
	po := f.upload(t, retailService, domain.PurposePurchaseOrder, "PO-7.pdf", domain.ContentTypePDF, pdf())
	if _, err := f.svc.Get(ctx, staff, po.ID, 0); err != nil {
		t.Errorf("staff without kyc:read reading a purchase order: %v", err)
	}
}
