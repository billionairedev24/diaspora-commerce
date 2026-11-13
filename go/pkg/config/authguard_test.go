package config

import "testing"

func TestRequireAuthOutsideLocal(t *testing.T) {
	for _, tc := range []struct {
		env     string
		enabled bool
		wantErr bool
	}{
		{"prod", true, false}, {"uat", true, false}, {"dev", true, false},
		{"local", false, false}, {"test", false, false},
		{"prod", false, true}, {"uat", false, true}, {"dev", false, true}, {"PROD", false, true}, {"", false, true}, {"staging", false, true},
	} {
		err := RequireAuthOutsideLocal(tc.env, tc.enabled)
		if (err != nil) != tc.wantErr {
			t.Errorf("env=%q enabled=%v: err=%v, wantErr=%v", tc.env, tc.enabled, err, tc.wantErr)
		}
	}
}
