// Command indexer projects catalog and inventory events into the search read model.
package main

import (
	"log/slog"
	"os"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/module"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/config"
)

func main() {
	// `indexer reindex` rebuilds the index from the sources of truth and exits (reindex.go); without a subcommand
	// the process is the Kafka consumer.
	if len(os.Args) > 1 && os.Args[1] == reindexCommand {
		if err := runReindex(os.Args[2:]); err != nil {
			slog.Error("reindex failed", "error", err)
			os.Exit(1)
		}
		return
	}
	module.Main(indexer.Name, indexer.New)
}

// loadConfig reads config/<APP_ENV>.yaml with the environment overrides (shared by the consumer and the reindex).
func loadConfig() (config.Config, string, error) { return indexer.LoadConfig(module.Deps{}) }
