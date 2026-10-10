package authzsync_test

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"

	authzsync "github.com/billionairedev24/diaspora-commerce/go/services/authz-sync"
)

// TestEmbeddedModelMatchesContract keeps model/model.json identical to contracts/openfga/model.json (regenerate with
// `fga model transform --file contracts/openfga/model.fga` and copy).
func TestEmbeddedModelMatchesContract(t *testing.T) {
	contract, err := os.ReadFile(filepath.Join("..", "..", "..", "contracts", "openfga", "model.json"))
	if err != nil {
		t.Skipf("contracts not available: %v", err)
	}
	embedded, err := authzsync.Files.ReadFile("model/model.json")
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(bytes.TrimSpace(contract), bytes.TrimSpace(embedded)) {
		t.Fatal("model/model.json differs from contracts/openfga/model.json; copy the contract")
	}
}

// TestEmbeddedContractIsIdentical keeps api/openapi.yaml (served at /openapi.yaml and /docs) equal to the contract.
func TestEmbeddedContractIsIdentical(t *testing.T) {
	want, err := os.ReadFile(filepath.Join("..", "..", "..", "contracts", "openapi", "authz-sync.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	got, err := authzsync.Files.ReadFile("api/openapi.yaml")
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, want) {
		t.Fatal("api/openapi.yaml differs from contracts/openapi/authz-sync.yaml; copy the contract")
	}
}
