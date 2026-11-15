// Command notifications sends templated, consent-checked email, SMS, push and WhatsApp messages with
// per-market provider failover, and records delivery receipts.
package main

import (
	"github.com/billionairedev24/diaspora-commerce/go/pkg/module"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications"
)

func main() { module.Main(notifications.Name, notifications.New) }
