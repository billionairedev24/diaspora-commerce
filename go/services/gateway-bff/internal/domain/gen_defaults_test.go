package domain

import (
	"encoding/json"
	"os"
	"testing"
)

const defaultsFile = "../../../../../contracts/settings/home-defaults.json"

// The documented defaults live in contracts/settings/home-defaults.json; content (Java) and the gateway each assert they
// equal it, so the two services can never disagree about what loads when no settings row exists.
// Regenerate with: UPDATE_HOME_DEFAULTS=1 go test ./internal/domain -run TestHomeDefaultsEqualTheSharedFile
func TestHomeDefaultsEqualTheSharedFile(t *testing.T) {
	want, err := json.MarshalIndent(DefaultHomeSettings().Merged(), "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	want = append(want, '\n')
	if os.Getenv("UPDATE_HOME_DEFAULTS") == "1" {
		if err := os.WriteFile(defaultsFile, want, 0o644); err != nil {
			t.Fatal(err)
		}
	}
	got, err := os.ReadFile(defaultsFile)
	if err != nil {
		t.Skip("contracts not available: ", err)
	}
	var a, b any
	_ = json.Unmarshal(got, &a)
	_ = json.Unmarshal(want, &b)
	ga, _ := json.Marshal(a)
	gb, _ := json.Marshal(b)
	if string(ga) != string(gb) {
		t.Fatalf("contracts/settings/home-defaults.json differs from DefaultHomeSettings(); regenerate it and update content's HomeSettings.defaults()")
	}
}
