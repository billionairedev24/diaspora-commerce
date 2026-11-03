package authzsync_test

import (
	"bytes"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"testing"

	"gopkg.in/yaml.v3"

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

// TestServiceClientsMatchIdentity keeps every environment's serviceClients equal to the confidential
// client_credentials clients identity registers for that environment (S266): a new first-party service client must be
// listed here or FGA enforcement blocks its service-to-service calls.
func TestServiceClientsMatchIdentity(t *testing.T) {
	for _, env := range []string{"local", "dev", "uat", "prod"} {
		identity, err := os.ReadFile(filepath.Join("..", "..", "..", "java", "services", "identity", "src", "main", "resources", "application-"+env+".yml"))
		if err != nil {
			t.Skipf("identity configuration not available: %v", err)
		}
		var want []string
		for _, block := range strings.Split(string(identity), "\n      - client-id: ")[1:] {
			grants := regexp.MustCompile(`grant-types: \[([^\]]*)\]`).FindStringSubmatch(block)
			if grants != nil && strings.Contains(grants[1], "client_credentials") && strings.Contains(block, "type: confidential") {
				want = append(want, strings.TrimSpace(strings.SplitN(block, "\n", 2)[0]))
			}
		}
		raw, err := authzsync.Files.ReadFile("config/" + env + ".yaml")
		if err != nil {
			t.Fatal(err)
		}
		var cfg struct {
			ServiceClients []string `yaml:"serviceClients"`
		}
		if err := yaml.Unmarshal(raw, &cfg); err != nil {
			t.Fatal(err)
		}
		slices.Sort(want)
		got := slices.Clone(cfg.ServiceClients)
		slices.Sort(got)
		if !slices.Equal(got, want) {
			t.Errorf("%s: serviceClients = %v, identity registers %v", env, got, want)
		}
	}
}
