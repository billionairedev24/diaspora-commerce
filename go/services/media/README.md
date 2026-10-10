# media

Direct uploads and file inspection. Go 1.27.1 · PostgreSQL · local port **8207**.

## Flow

1. `POST /v1/uploads {purpose, fileName, contentType, size}` — the declared file is checked against the purpose rules
   and the service returns a short-lived upload URL for the purpose's store.
2. The browser or app `PUT`s the file **directly to storage** (no file bytes pass through our servers). When
   `upload.formField` is set (UploadThing) the file goes as multipart/form-data in that field, with `upload.headers`.
3. `POST /v1/assets/{id}/complete` — the service reads the object back, **detects the real type from the bytes**
   (a renamed HTML file never passes as a JPEG), measures it and applies the rules. Rejected files are deleted.
   Public images then go through moderation (below) and the response carries `url`, `width`, `height`, `bytes`,
   `contentType`, `status`, `flags` and `moderation` for the product builder.

### Moderation of public images (S030)

`product-image` and `storefront` uploads land in **quarantine** (`quarantine/<key>` on S3-compatible stores — the
bucket policy must make only the purpose prefixes public, never `quarantine/*`; UploadThing uploads are `private`) and
are published only when moderation passes:

1. **Rules**: real type, size, minimum edge, white background / main-image eligibility (quality flags never block).
2. **Metadata stripping** (`internal/adapter/imageproc`): EXIF (GPS, camera), XMP, IPTC, comments and PNG/WebP text
   chunks are removed losslessly; a JPEG with an EXIF orientation is rotated and re-encoded so it still displays
   upright. Images over `moderation.maxPixels` (40 MP) are rejected.
3. **Duplicates**: a 64-bit difference hash (dHash) is compared by Hamming distance (≤ `duplicateDistance`, 6) with
   completed public images (`asset.phash`, migration `002`). Another organisation's near-identical photo holds the
   image for review (possibly lifted from another listing); the organisation's own is only noted (`duplicateOf`).
4. **AI hook** (`internal/adapter/aimoderation` → ai-gateway `POST /v1/ai/moderate-image`, a 768 px JPEG preview,
   client `media-service` with scope `ai:use`): `ALLOW`, `REVIEW` (watermarks, contact details, text overlays, not a
   product photo …) or `BLOCK` (prohibited content). When the hook fails or times out, `failMode: closed` (uat, prod)
   holds the image and `open` (dev) publishes it with the note "AI moderation skipped".

`moderation.verdict` is `PASSED` (the cleaned image is written under its public key, the quarantined upload deleted,
`url` public), `HELD` (status `FLAGGED`, private; the owner and staff see a 5-minute signed URL) or `BLOCKED` (status
`REJECTED`, deleted). Staff with `media:moderate` or `catalog:moderate` release or reject held images with
`POST /v1/assets/{id}/review {decision: APPROVE|REJECT, note}`. Updates are optimistic (`asset.version`), so two
concurrent completions publish once. Tests use fakes and httptest servers: UploadThing and the AI are never called.

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

### Service clients (S243) and BFF-forwarded tokens (S250)

identity issues client-credentials tokens with the client id as audience and subject (principal type `SERVICE`),
so besides tokens for its own audience media accepts the **own service tokens of `retail-service` and
`gateway-bff`** (`internal/adapter/authn`). Those two clients may use only the document purposes above, which in turn
refuse every other service client; human callers of `purchase-order`, `shipping-document` and `export` must be staff.
Existing purposes keep their rules (owner org, uploader or staff/service).

**User tokens forwarded by the BFF** (`auth.forwardedAudiences: [gateway-bff]`): a signed-in user's access token
minted for the BFF's audience is accepted **as that user** — sellers uploading product images through Seller Center,
signed-in applicants uploading résumés, staff storing exports from the staff console — so every purpose rule applies
to the user, never to the BFF. A service token of another client with that audience is refused.

### Signed download links

Private assets come with a signed `url` and its `urlExpiresAt`. `GET /v1/assets/{id}?urlTtlSeconds=N` asks for a
lifetime: the document purposes get up to `links.maxLongTtl` (`SIGNED_URL_MAX_TTL`, default 14 days, e.g. for the PO
link e-mailed to suppliers); every other purpose stays at `links.defaultTtl` (`SIGNED_URL_DEFAULT_TTL`, 5 minutes).
Longer requests are capped, not refused, and `urlExpiresAt` reports what was granted.

**Media-hosted long-lived links (S250).** Storage signers cap lifetimes — S3-compatible stores (AWS S3, R2, GCS via
HMAC, MinIO) and UploadThing at 7 days, and on AWS with IRSA a presigned URL dies with the session credentials
(hours). So with `LINK_SIGNING_KEY` set, a request above the default lifetime returns a **media-hosted link**
`<publicBaseUrl>/v1/l/<token>` (`publicBaseUrl` defaults to `<API_BASE_URL>/media`) valid for the granted lifetime
(14 days for the PO e-mailed to suppliers) on every cloud: `GET /v1/l/{token}` needs no bearer token, checks the
HMAC-SHA256 token (asset id + expiry; `LINK_SIGNING_KEYS_PREVIOUS` still verify after a rotation) and redirects (302,
`no-store`, `no-referrer`) to a fresh storage URL of `links.redirectTtl` (5 minutes). Expired links answer 410, forged
ones 404, and only document purposes are served. Without the key, long links stay storage-signed and capped as above.
notifications fetches PO attachments from these links (its allow-list adds `<API_BASE_URL>/media/v1/l/`).

Stores implement one `ObjectStore` port: **UploadThing** (REST v7), **S3-compatible** (AWS S3, Cloudflare R2, Google
Cloud Storage via HMAC interoperability, MinIO locally) and **Azure Blob** (SAS). Which store serves which purpose is
configuration (`stores` + `purposes` in `config/<env>.yaml`), so the cloud decision stays open.

Contract: [`contracts/openapi/media.yaml`](../../../contracts/openapi/media.yaml).

## Configuration

`DB_URL`, `UPLOADTHING_API_KEY` + `UPLOADTHING_APP_ID` or `UPLOADTHING_TOKEN` (the dashboard's base64 token),
`R2_ACCESS_KEY_ID`/`R2_SECRET_ACCESS_KEY`, `AZURE_STORAGE_KEY`, `MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY` (local),
`AUTH_ENABLED`, `AUTH_ISSUER_URL`, `AUTH_AUDIENCE`, `AUTH_FORWARDED_AUDIENCES` (`gateway-bff`), `SIGNED_URL_DEFAULT_TTL`
(5m), `SIGNED_URL_MAX_TTL` (336h), `LINK_SIGNING_KEY` (secret, ≥ 32 characters), `LINK_SIGNING_KEYS_PREVIOUS`,
`LINK_REDIRECT_TTL`, `API_BASE_URL` / `PUBLIC_BASE_URL`, `AI_GATEWAY_URL`, `MODERATION_AI_ENABLED`,
`MODERATION_AI_FAIL_MODE` (`open` | `closed`), `MODERATION_AI_TIMEOUT`, `MODERATION_DUPLICATE_DISTANCE`,
`MODERATION_MAX_PIXELS`, `AUTH_TOKEN_URL`, `MEDIA_CLIENT_ID` (`media-service`), `MEDIA_CLIENT_SECRET` (secret),
`MEDIA_CLIENT_SCOPES` (`ai:use`). AWS credentials come from the default chain (IRSA on EKS).

## Run and test

```bash
cd go/services/media
GOTOOLCHAIN=go1.27.2 go test ./...   # rules, link policy, inspector (CSV/XLSX), service-client and forwarded tokens,
                                     # handlers, UploadThing (httptest: private uploads, multipart ingest, local URL
                                     # signing), metadata stripping + dHash, moderation flows with a fake AI hook,
                                     # media-hosted links, MinIO and PostgreSQL (postgres:17) Testcontainers
```
