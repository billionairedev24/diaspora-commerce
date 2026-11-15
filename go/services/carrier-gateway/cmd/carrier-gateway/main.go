// Command carrier-gateway integrates USPS, UPS, FedEx and DHL Express directly (no aggregator): rate shopping,
// labels, voids, tracking ingestion (webhooks + USPS polling) with an outbox to Kafka, pickups, pickup
// locations and carrier-side address validation.
package main

import (
	"github.com/billionairedev24/diaspora-commerce/go/pkg/module"
	carriergateway "github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway"
)

func main() { module.Main(carriergateway.Name, carriergateway.New) }
