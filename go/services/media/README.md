# media

Direct uploads and file inspection. Go 1.27.1 · PostgreSQL · local port **8207**.

## Flow

1. `POST /v1/uploads {purpose, fileName, contentType, size}` — the declared file is checked against the purpose rules
   and the service returns a short-lived upload URL for the purpose's store.
2. The browser or app `PUT`s the file **directly to storage** (no file bytes pass through our servers).
3. `POST /v1/assets/{id}/complete` — the service reads the object back, **detects the real type from the bytes**
   (a renamed HTML file never passes as a JPEG), measures it and applies the rules. Rejected files are deleted.

| Purpose | Store (dev/uat/prod) | Visibility | Rules |
|---|---|---|---|
| `product-image` | **UploadThing** (R2 as alternative) | public CDN | JPG/PNG/WebP ≤ 8 MB, ≥ 500 px; flags non-white backgrounds; ≥ 1000 px marked "eligible as main image" |
| `storefront` | UploadThing | public | JPG/PNG/WebP ≤ 8 MB |
| `proof-photo`, `return-photo` | private bucket | 5-minute signed URLs | images ≤ 10 MB |
| `kyc-document` | private, object-locked bucket on the primary cloud | 5-minute signed URLs, owner or staff only | images or PDF ≤ 15 MB |
| `purchase-order` | private bucket | long-lived signed URLs; staff, `retail-service`, `gateway-bff` | PDF ≤ 10 MB |
| `shipping-document` | private bucket | long-lived signed URLs; staff, `retail-service`, `gateway-bff` | PDF or JPG/PNG/WebP ≤ 15 MB |
| `career-resume` | private bucket | long-lived signed URLs; the applicant, staff, `retail-service`, `gateway-bff` (guests) | PDF ≤ 5 MB |
| `export` | private bucket | long-lived signed URLs; staff, `retail-service`, `gateway-bff` | CSV or XLSX ≤ 50 MB |

The real type is checked on completion for every purpose: CSV must sniff as plain text (HTML, scripts and binaries
declared as CSV are rejected) and XLSX must be a ZIP package with `[Content_Types].xml` and `xl/workbook.xml` (read
from the local file headers in the first 16 MB).

### Service clients (S243)

identity issues client-credentials tokens with the client id as audience and subject (principal type `SERVICE`),
so besides tokens for its own audience media accepts the **own service tokens of `retail-service` and
`gateway-bff`** (`internal/adapter/authn`; a user token minted for the BFF, which has the BFF as audience but a user
as subject, is still refused). Those two clients may use only the document purposes above, which in turn refuse every
other service client; human callers of `purchase-order`, `shipping-document` and `export` must be staff. Existing
purposes keep their rules (owner org, uploader or staff/service).

### Signed download links

Private assets come with a signed `url` and its `urlExpiresAt`. `GET /v1/assets/{id}?urlTtlSeconds=N` asks for a
lifetime: the document purposes get up to `links.maxLongTtl` (`SIGNED_URL_MAX_TTL`, default 14 days, e.g. for the PO
link e-mailed to suppliers); every other purpose stays at `links.defaultTtl` (`SIGNED_URL_DEFAULT_TTL`, 5 minutes).
Longer requests are capped, not refused, and `urlExpiresAt` reports what was granted.

The store caps it too: **S3-compatible stores (AWS S3, R2, GCS via HMAC, MinIO) and UploadThing sign for at most
7 days**, so on those a 14-day request gets a 7-day link; only Azure Blob (account-key SAS) signs for 14 days. On
AWS with IRSA (temporary credentials) a presigned URL also dies when the session credentials expire (hours), so
long links on S3 need static credentials for the private store, or a media-hosted redirect link (not built).

Stores implement one `ObjectStore` port: **UploadThing** (REST v7), **S3-compatible** (AWS S3, Cloudflare R2, Google
Cloud Storage via HMAC interoperability, MinIO locally) and **Azure Blob** (SAS). Which store serves which purpose is
configuration (`stores` + `purposes` in `config/<env>.yaml`), so the cloud decision stays open.

Contract: [`contracts/openapi/media.yaml`](../../../contracts/openapi/media.yaml).

## Configuration

`DB_URL`, `UPLOADTHING_API_KEY`, `UPLOADTHING_APP_ID`, `R2_ACCESS_KEY_ID`/`R2_SECRET_ACCESS_KEY`, `AZURE_STORAGE_KEY`,
`MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY` (local), `AUTH_ENABLED`, `AUTH_ISSUER_URI`, `AUTH_AUDIENCE`,
`SIGNED_URL_DEFAULT_TTL` (5m), `SIGNED_URL_MAX_TTL` (336h). AWS credentials come from the default
chain (IRSA on EKS).

## Run and test

```bash
cd go/services/media
GOTOOLCHAIN=go1.27.1 go test ./...   # rules, link policy, inspector (CSV/XLSX), service-client tokens, handlers,
                                     # UploadThing (httptest), full flow incl. document purposes, MinIO Testcontainers
```
