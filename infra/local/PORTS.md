# Service catalogue and local ports

| Service | Runtime | Local port | Owns | Store |
|---|---|---|---|---|
| identity | Spring Boot (Authorization Server) | 8100 | users, roles, permissions, seller/provider orgs, OAuth clients, MFA, passkeys | Postgres `identity` |
| catalog | Spring Boot | 8101 | products (ATIN/GTIN), variants, categories, attributes, category requests | Postgres `catalog` |
| offers | Spring Boot | 8102 | seller offers, pricing, buy box inputs, coupons | Postgres `offers` |
| inventory | Spring Boot | 8103 | stock positions, lots, reservations, ledger | Postgres `inventory` |
| orders | Spring Boot | 8104 | checkout saga, orders, shipments, status state machine | Postgres `orders` + Temporal |
| payments | Spring Boot | 8105 | Stripe intents, transfers, refunds, double-entry ledger, payouts | Postgres `payments` |
| seller | Spring Boot | 8106 | onboarding applications, two-step review, Connect compliance, plans & billing | Postgres `seller` |
| retail | Spring Boot | 8107 | suppliers, purchase orders, inbound shipments/ASN, goods receipts | Postgres `retail` |
| fulfilment | Spring Boot | 8108 | FCs, pick/pack, dispatch, driver routes, proof of delivery | Postgres `fulfilment` |
| bookings | Spring Boot | 8109 | services, availability, bookings, cancellation policies | Postgres `bookings` |
| trust-ops | Spring Boot | 8110 | moderation, disputes, audit log | Postgres `trustops` |
| tax-returns | Spring Boot | 8111 | facilitator tax, RMAs, refunds orchestration | Postgres `taxreturns` |
| content | Spring Boot | 8112 | announcements, placements, ads campaigns, site/legal config, markets | Postgres `content` |
| ai-gateway | Spring Boot + Spring AI | 8113 | Lola agent, OpenRouter routing, budgets, redaction | Postgres `ai` |
| mcp-server | Spring Boot + Spring AI MCP | 8114 | MCP tools for shoppers, sellers, staff | — |
| delivery-runs | Spring Boot | 8115 | community delivery runs: locations, runs (cutoff, minimum, capacity), run slots, subscriptions, run economics | Postgres `deliveryruns` |
| gateway-bff | Go | 8200 | screen-shaped APIs for web/mobile, token relay | Redis |
| search | Go | 8201 | query, facets, autocomplete, hybrid retrieval | Elasticsearch |
| indexer | Go | 8202 | Kafka → Elasticsearch projection | Elasticsearch |
| cart | Go | 8203 | carts, save-for-later, merge on login | Redis + Postgres `cart` |
| promise | Go | 8204 | delivery promises per ZIP | Redis |
| geo | Go | 8205 | address autocomplete (Google Places / OSM Nominatim), validation | Postgres/PostGIS `geo` |
| storefront | Go | 8206 | seller/provider storefront themes & layouts | Postgres `storefront` |
| media | Go | 8207 | upload signing (UploadThing / S3-compatible), image metadata | Postgres `media` |
| notifications | Go | 8208 | email (Resend, SendGrid, SES), SMS (Twilio, Vonage), push, WhatsApp | Postgres `notifications` |
| carrier-gateway | Go | 8209 | USPS, UPS, FedEx, DHL rates/labels/tracking | Postgres `carriers` |
| authz-sync | Go | 8210 | identity/seller events → OpenFGA relationship tuples | OpenFGA |

| Front-end | Port |
|---|---|
| web/apps/storefront | 3000 |
| web/apps/seller-center | 3001 |
| web/apps/staff-console | 3002 |
| docs/portal (Docusaurus) | 3100 |

| Infrastructure | Port |
|---|---|
| PostgreSQL 17 (+PostGIS) | 5432 |
| Redis / Valkey | 6379 |
| Kafka (KRaft) | 9092 |
| Kafka UI | 8090 |
| Elasticsearch 9 | 9200 |
| Kibana | 5601 |
| Temporal / UI | 7233 / 8088 |
| OTel Collector (gRPC/HTTP) | 4317 / 4318 |
| Prometheus | 9090 |
| Alertmanager | 9093 |
| Grafana | 3200 |
| Loki | 3300 |
| Tempo | 3201 |
| Mailpit (SMTP / UI) | 1025 / 8025 |
| MinIO (S3 / console) | 9000 / 9001 |
| OpenFGA | 8091 |
