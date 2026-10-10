package domain

import "time"

// Seller pickups of AfroTransact Delivery from the seller's store (S255): the staff dispatch screen
// (gateway-bff-staff.yaml, tag Dispatch) and the Seller Center's pickup locations (gateway-bff-seller.yaml).

// PickupWindow is one pickup window: a weekday and 24-hour local times (HH:MM).
type PickupWindow struct {
	Day    string `json:"day"`
	Opens  string `json:"opens"`
	Closes string `json:"closes"`
}

// PickupRow is a seller pickup waiting in the dispatch pool.
type PickupRow struct {
	ShipmentID  string     `json:"shipmentId"`
	OrderNumber string     `json:"orderNumber"`
	SellerOrgID string     `json:"sellerOrgId"`
	SellerName  string     `json:"sellerName"`
	City        string     `json:"city"`
	State       string     `json:"state"`
	PostalCode  string     `json:"postalCode"`
	Status      string     `json:"status"`
	RouteID     *string    `json:"routeId"`
	Perishable  bool       `json:"perishable"`
	Units       int        `json:"units"`
	PromisedBy  *string    `json:"promisedBy"`
	CreatedAt   *time.Time `json:"createdAt"`
}

// PickupList is GET /v1/staff/dispatch/pickups.
type PickupList struct {
	Items     []PickupRow `json:"items"`
	CanAssign bool        `json:"canAssign"`
}

// DriverSuggestion is an available driver for a pickup, nearest first.
type DriverSuggestion struct {
	DriverID          string     `json:"driverId"`
	RouteID           string     `json:"routeId"`
	RouteName         string     `json:"routeName"`
	Zone              string     `json:"zone"`
	RouteStatus       string     `json:"routeStatus"`
	StraightLineMiles float64    `json:"straightLineMiles"`
	RoadMiles         *float64   `json:"roadMiles"`
	DurationMinutes   *float64   `json:"durationMinutes"`
	DistanceMethod    string     `json:"distanceMethod"`
	PositionAt        *time.Time `json:"positionAt"`
	PackagesOnRoute   int        `json:"packagesOnRoute"`
	CapacityLeft      int        `json:"capacityLeft"`
}

// DriverSuggestions is GET /v1/staff/dispatch/pickups/{id}/driver-suggestions (top 5).
type DriverSuggestions struct {
	ShipmentID string             `json:"shipmentId"`
	Drivers    []DriverSuggestion `json:"drivers"`
	CanAssign  bool               `json:"canAssign"`
}

// PickupAssignRequest is POST /v1/staff/dispatch/pickups/{id}/assign.
type PickupAssignRequest struct {
	RouteID string `json:"routeId"`
	Reason  string `json:"reason"`
}

// PickupAssignResult is the route the pickup joined.
type PickupAssignResult struct {
	ShipmentID string  `json:"shipmentId"`
	RouteID    string  `json:"routeId"`
	RouteName  *string `json:"routeName"`
	DriverID   *string `json:"driverId"`
	Status     string  `json:"status"`
}

// PickupAssignment is one assignment of a pickup (automatic nearest driver or dispatch override).
type PickupAssignment struct {
	ID              string     `json:"id"`
	ShipmentID      string     `json:"shipmentId"`
	RouteID         string     `json:"routeId"`
	DriverID        *string    `json:"driverId"`
	Mode            string     `json:"mode"`
	Reason          *string    `json:"reason"`
	PreviousRouteID *string    `json:"previousRouteId"`
	StraightMiles   *float64   `json:"straightMiles"`
	RoadMiles       *float64   `json:"roadMiles"`
	DistanceMethod  *string    `json:"distanceMethod"`
	Actor           *string    `json:"actor"`
	CreatedAt       *time.Time `json:"createdAt"`
}

// PickupAssignmentList is GET /v1/staff/dispatch/pickups/{id}/assignments.
type PickupAssignmentList struct {
	Items []PickupAssignment `json:"items"`
}

// PickupPosition is a geocoded position.
type PickupPosition struct {
	Lat       float64 `json:"lat"`
	Lng       float64 `json:"lng"`
	Precision string  `json:"precision"`
}

// ShipFromLocation is GET /v1/staff/ship-from-locations/{id}.
type ShipFromLocation struct {
	ID            string          `json:"id"`
	OrgID         string          `json:"orgId"`
	Label         string          `json:"label"`
	Line1         string          `json:"line1"`
	Line2         string          `json:"line2"`
	City          string          `json:"city"`
	State         string          `json:"state"`
	PostalCode    string          `json:"postalCode"`
	Country       string          `json:"country"`
	Position      *PickupPosition `json:"position"`
	PickupEnabled bool            `json:"pickupEnabled"`
	PickupHours   []PickupWindow  `json:"pickupHours"`
	ZoneName      *string         `json:"zoneName"`
}
