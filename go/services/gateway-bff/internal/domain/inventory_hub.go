package domain

import "time"

// Inventory hub (S281): the pipeline from a purchase order to "Sold & shipped by AfroTransact", and the trace of one
// product along it (contracts/openapi/gateway-bff-staff.yaml, tag Inventory hub).

// FlowStage is one step of the pipeline with what is waiting at it. Count is nil when the service behind the step
// could not be reached (Unavailable names it).
type FlowStage struct {
	Key         string `json:"key"`
	Title       string `json:"title"`
	Who         string `json:"who"`
	What        string `json:"what"`
	Effect      string `json:"effect"`
	Count       *int64 `json:"count"`
	CountLabel  string `json:"countLabel"`
	Href        string `json:"href"`
	Unavailable string `json:"unavailable,omitempty"`
}

// InventoryFlow is the pipeline with live counts.
type InventoryFlow struct {
	Stages []FlowStage `json:"stages"`
	AsOf   time.Time   `json:"asOf"`
}

// TraceStep is one link of a product's trace: done, waiting or broken, with what was found.
type TraceStep struct {
	Key    string `json:"key"`
	Title  string `json:"title"`
	Status string `json:"status"`
	Detail string `json:"detail"`
	Href   string `json:"href,omitempty"`
}

// InventoryTrace follows one product (by ATIN or inventory SKU) from its retail item to the storefront.
type InventoryTrace struct {
	Atin           string      `json:"atin,omitempty"`
	Sku            string      `json:"sku,omitempty"`
	Title          string      `json:"title,omitempty"`
	Steps          []TraceStep `json:"steps"`
	AvailableUnits *int64      `json:"availableUnits"`
	StorefrontPath string      `json:"storefrontPath,omitempty"`
	Badges         []string    `json:"badges"`
	DeliveryDays   *int64      `json:"deliveryDays"`
}

// Trace step statuses.
const (
	TraceDone    = "DONE"
	TraceWaiting = "WAITING"
	TraceBroken  = "BROKEN"
)
