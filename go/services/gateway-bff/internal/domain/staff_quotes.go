package domain

// Delivery quotes in the staff console (Customer care): shoppers outside the delivery zones ask for a quote, customer
// care agrees an amount or declines (listing rules D20). orders-service owns the case, its queue and the rules; the BFF
// shows what it computes and relays every action. docs/runbooks/delivery-quotes.md.

// DeliveryQuotesModule is the staff console module key of Delivery quotes.
const DeliveryQuotesModule = "deliveryQuotes"

// orders-service permissions behind Delivery quotes (identity migration V41).
const (
	// PermQuotesView sees the queue, the cases and their timelines.
	PermQuotesView = "delivery-quotes:view"
	// PermQuotesAgree claims a case, agrees an amount (with a reason) or declines.
	PermQuotesAgree = "delivery-quotes:agree"
	// PermQuotesAssign assigns and takes over cases and changes the SLA, escalation and amount settings.
	PermQuotesAssign = "delivery-quotes:assign"
)
