module github.com/billionairedev24/diaspora-commerce/go/apps/afrotransact-go

go 1.27.1

toolchain go1.27.4

require (
	github.com/billionairedev24/diaspora-commerce/go/pkg v0.0.0
	github.com/billionairedev24/diaspora-commerce/go/services/authz-sync v0.0.0
	github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway v0.0.0
	github.com/billionairedev24/diaspora-commerce/go/services/cart v0.0.0
	github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff v0.0.0
	github.com/billionairedev24/diaspora-commerce/go/services/geo v0.0.0
	github.com/billionairedev24/diaspora-commerce/go/services/indexer v0.0.0
	github.com/billionairedev24/diaspora-commerce/go/services/media v0.0.0
	github.com/billionairedev24/diaspora-commerce/go/services/notifications v0.0.0
	github.com/billionairedev24/diaspora-commerce/go/services/promise v0.0.0
	github.com/billionairedev24/diaspora-commerce/go/services/search v0.0.0
	github.com/prometheus/client_golang v1.25.0
	github.com/prometheus/client_model v0.6.3
	go.opentelemetry.io/otel/sdk v1.47.0
	gopkg.in/yaml.v3 v3.0.1
)

replace (
	github.com/billionairedev24/diaspora-commerce/go/pkg => ../../pkg
	github.com/billionairedev24/diaspora-commerce/go/services/authz-sync => ../../services/authz-sync
	github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway => ../../services/carrier-gateway
	github.com/billionairedev24/diaspora-commerce/go/services/cart => ../../services/cart
	github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff => ../../services/gateway-bff
	github.com/billionairedev24/diaspora-commerce/go/services/geo => ../../services/geo
	github.com/billionairedev24/diaspora-commerce/go/services/indexer => ../../services/indexer
	github.com/billionairedev24/diaspora-commerce/go/services/media => ../../services/media
	github.com/billionairedev24/diaspora-commerce/go/services/notifications => ../../services/notifications
	github.com/billionairedev24/diaspora-commerce/go/services/promise => ../../services/promise
	github.com/billionairedev24/diaspora-commerce/go/services/search => ../../services/search
)
