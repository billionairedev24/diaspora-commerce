package domain

import "testing"

func TestDriverModulesAreLabelledDormantAndNothingIsHidden(t *testing.T) {
	access := StaffAccess{Roles: []string{"platform.super_admin"}, Permissions: []string{PermQuotesView}, Matrix: ModuleMatrix{}}
	for _, m := range []string{"dispatch", DeliveryModule, DeliveryQuotesModule, "orders"} {
		access.Matrix[m] = map[string]string{"platform.super_admin": LevelFull}
	}
	nav := StaffNavigation(access, nil)
	MarkDormant(nav)
	got := map[string]StaffNavModule{}
	for _, g := range nav {
		for _, m := range g.Modules {
			got[m.Key] = m
		}
	}
	for _, key := range []string{"dispatch", DeliveryQuotesModule} {
		if m, ok := got[key]; !ok || !m.Dormant || m.Description == "" {
			t.Fatalf("%s stays reachable and is labelled dormant: %+v", key, m)
		}
	}
	for _, key := range []string{DeliveryModule, "orders"} {
		if m, ok := got[key]; !ok || m.Dormant {
			t.Fatalf("%s is in use: %+v", key, m)
		}
	}
}
