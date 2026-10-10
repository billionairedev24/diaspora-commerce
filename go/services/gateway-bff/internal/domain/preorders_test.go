package domain

import "testing"

func TestParseAttributionCookie(t *testing.T) {
	for in, want := range map[string]*Attribution{
		"src=ig&cr=story&po=lbk-7":                            {Src: "ig", Cr: "story", Po: "LBK-7"},
		`{"src":"email","cr":"announce","po":"LBK-7"}`:        {Src: "email", Cr: "announce", Po: "LBK-7"},
		"%7B%22src%22%3A%22wa%22%2C%22po%22%3A%22LBK-7%22%7D": {Src: "wa", Po: "LBK-7"},
		"src=<script>&po=LBK-7":                               {Po: "LBK-7"},
		"src=<script>":                                        nil,
		"":                                                    nil,
		"{broken":                                             nil,
	} {
		got := ParseAttributionCookie(in)
		if (got == nil) != (want == nil) || (got != nil && *got != *want) {
			t.Errorf("%q = %+v, want %+v", in, got, want)
		}
	}
}

func TestPreorderPlacesAndKeys(t *testing.T) {
	p, errs := NormalizePlace(" us", "tx ", "  Lubbock   ")
	if len(errs) > 0 || p.Key() != "US/TX/Lubbock" {
		t.Fatalf("place = %+v %v", p, errs)
	}
	if _, errs := NormalizePlace("USA", "Texas", "a/b"); len(errs) != 3 {
		t.Fatalf("errs = %v", errs)
	}
	for key, ok := range map[string]bool{"US/TX": true, "US/TX/Lubbock": true, "US": false, "US/TX/": false, "US/TX/a/b": false, "Texas": false} {
		if ValidAreaKey(key) != ok {
			t.Errorf("%q valid = %v", key, !ok)
		}
	}
}

func TestPreorderPermissions(t *testing.T) {
	support := StaffAccess{Roles: []string{"platform.customer_support"}, Permissions: []string{PermPreordersLateAdd, PermPreordersManage},
		Matrix: DefaultPlatformMatrix}
	if c := PreorderPermissions(support); c.Manage || c.Settings || !c.LateAdd {
		t.Fatalf("support (APPROVE) = %+v", c)
	}
	ops := StaffAccess{Roles: []string{"platform.operations_manager"}, Permissions: []string{PermPreordersManage, PermPreordersLateAdd, PermPreordersExport},
		Matrix: DefaultPlatformMatrix}
	if c := PreorderPermissions(ops); !c.Manage || c.Settings || !c.LateAdd || !c.Export {
		t.Fatalf("operations (EDIT) = %+v", c)
	}
	admin := StaffAccess{Roles: []string{"platform.super_admin"}, Permissions: []string{PermPreordersManage}, Matrix: DefaultPlatformMatrix}
	if c := PreorderPermissions(admin); !c.Manage || !c.Settings || c.LateAdd {
		t.Fatalf("super admin without late-add = %+v", c)
	}
}
