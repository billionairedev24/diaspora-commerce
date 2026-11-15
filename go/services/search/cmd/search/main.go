// Command search serves product search and typeahead over Meilisearch or Elasticsearch, with sponsored products and
// click tracking (search events go to Kafka).
package main

import (
	"github.com/billionairedev24/diaspora-commerce/go/pkg/module"
	"github.com/billionairedev24/diaspora-commerce/go/services/search"
)

func main() { module.Main(search.Name, search.New) }
