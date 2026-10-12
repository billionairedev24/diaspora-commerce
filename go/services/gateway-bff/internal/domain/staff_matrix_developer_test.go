package domain

import "testing"

func TestDeveloperDefaultMatrixOpensEveryStaffModule(t *testing.T) {
	access := StaffAccess{
		Roles: []string{"platform.developer"},
		Matrix: DefaultPlatformMatrix,
		Permissions: []string{"users:read", PermNotificationsRead, PermNotificationsTemplatesWrite, PermPlansManage, PermSellerBilling},
	}
	for _, g := range StaffNav {
		for _, m := range g.Modules {
			key := m.Key
			if key == "" {
				continue
			}
			if lv := access.Level(key); lv == LevelNone {
				t.Errorf("developer should see module %q; got NONE", key)
			}
		}
	}
	if lv := access.Level("marketing"); lv != LevelEdit {
		t.Fatalf("developer marketing level: want EDIT for coupon fixes, got %q", lv)
	}
}
