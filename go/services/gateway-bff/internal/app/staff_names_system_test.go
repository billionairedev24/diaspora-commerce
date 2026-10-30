package app

import "testing"

func TestSeedAndLoaderAreSystemActorsNotUnknownUsers(t *testing.T) {
	for _, id := range []string{"system", "system:seed", "seed", "legal-loader"} {
		if !isSystem(id) {
			t.Errorf("%q should be a system actor", id)
		}
	}
	for _, id := range []string{"", "user-1", "seeded-by-hand"} {
		if isSystem(id) {
			t.Errorf("%q should not be a system actor", id)
		}
	}
}
