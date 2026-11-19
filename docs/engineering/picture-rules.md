# Picture rules

One platform setting decides how many photos a product needs, how large they must be, which checks run, and whether the
storefront shows a product without a real photo. It has three named **profiles** (`strict`, `startup`, `testing`) and ten
**knobs** that staff can override one by one. It is staff-controlled, kept in the database, audited, and read by Java and Go
from the same document.

> **Defaults per environment (owner decision, first launch).** local and dev start on **`testing`**, uat and prod start on
> **`startup`**. The strict standard is kept, unchanged, as the documented profile. The owner will tighten prod with product later.
> Changing an environment's *starting* profile is one line: `at.catalog.picture-rules.profile` in
> `java/services/catalog/src/main/resources/application-<env>.yml` (and `PICTURE_RULES_PROFILE` in the Go services' `config/<env>.yaml`,
> which is only their fallback until catalog answers). A value staff saved in Settings > Picture rules always wins over it.

## The profiles

| Rule (knob) | `strict` (the standard, today's behaviour) | `startup` (relaxed launch) | `testing` (everything optional) |
|---|---|---|---|
| Photos needed to publish (`minImages`) | 1 | 1 | 0 (a product with no photo can be published) |
| Main photo, longest side at least (`minLongSidePx`) | 1000 px | 500 px | 1 px |
| Every photo, shortest side at least (`minShortSidePx`) | 500 px | 300 px | 1 px |
| Largest file (`maxMegabytes`) | 8 MB | 8 MB | 8 MB (platform ceiling 20 MB in every profile) |
| Plain white background, product fills the frame, no border (`checkBackground`) | on | off | off |
| Warn about very wide or tall photos (`checkAspect`) | on | off | off |
| Hold photos with text, logos or watermarks (`checkTextOverlay`) | on | off | off |
| Show only products with a real photo on home, rails, search (`requireRealPhotoForStorefront`) | on | on | off |
| Offer only pages with a real photo as matches to sellers (`requirePhotoForMatching`) | on | on | off |
| Count stand-in images as photos (`allowPlaceholders`) | off | off | on |

Not knobs, never relaxed by any profile: the accepted file types (**JPEG, PNG, WebP, AVIF**), sniffing of the real type from the
bytes, refusal of any file that carries markup or script (a polyglot), the size ceiling, the malware scan, prohibited-content
moderation, and "an SVG, an icon, an inline `data:` image or an emoji is never a photo".

A saved override changes one knob on top of a profile (for example `strict` with `minImages = 3`). Under `strict` with no override
nothing changes anywhere (media keeps its own configuration and staff override, the checks are today's).

## Where each rule lived, and what reads the setting now

| Rule | Where it was enforced | Now |
|---|---|---|
| Publish gate: "Add at least one image", type and size per image | `DefaultProductDraftPolicy` (catalog), `ProductImage.MAX_BYTES`, `ChangeRequestPolicy` | `minImages`, `maxMegabytes`, formats from `PictureRules` (catalog, read at every validation) |
| Main image 1000 px, white background, fill, border, blur, aspect | media `images.*` (`domain.ImageRules`, `EvaluateImage`) | media applies the profile on top of its own configuration (`domain.ApplyPictureRules`): `minLongSidePx`, `minShortSidePx`, `maxMegabytes`, `checkBackground`, `checkAspect`, `allowPlaceholders` |
| AI holds for text, logos, watermarks, props, mismatch | media `AIChecks` | `checkTextOverlay`; the testing profile skips the quality holds (prohibited content is always blocked) |
| Catalog's image gate (media verdict blocks the listing) | `ImageGateImpl` mode `enforce` | refuses only when the mode is `enforce` **and** the profile is not `testing` (`PictureRules.verdictsBlock`) |
| "Real photo" for candidate matching | `RealPhoto` + `CandidateMatchServiceImpl.shown` | `requirePhotoForMatching` + `allowPlaceholders` (`PictureRules.matchableWithPhoto`) |
| `photo_ok` in the search index and the `eligibleOnly` filter | `searchindex.PhotoOK`, search adapters | the index still stores the strict `photo_ok` (so changing the setting needs no reindex); the search service drops the photo filter when `requireRealPhotoForStorefront` is off (`Query.AnyPhoto`) |
| Home, rails, storefront products (`eligibleCard`) | gateway-bff `realPhoto` | `PictureRules.ShowOnStorefront`; editorial images (hero slides, tiles, department and community pictures) stay strict |
| Seller form: "Add at least one image", 1000 px, 8 MB, types | Seller Center constants, gateway-bff validation | Seller Center reads `GET /v1/seller/picture-rules` and words every message from the active rule; gateway-bff validates with it |
| Candidate / upload file types | JPEG, PNG, WebP | JPEG, PNG, WebP, **AVIF** everywhere on the product path |
| Seeder demo photos | `MAIN_MIN_EDGE = 1000`, "skipped: no image" | the seeder reads the rule in force first; under `testing` it creates a product without a photo and says so |

Not product pictures and therefore **not** governed by this setting: delivery proof photos, help-request evidence, review photos
and KYC documents (their own purposes and limits are unchanged).

## How the setting is shared

* **Owner:** catalog-service (`picture_rules_setting` and `picture_rules_audit`, Flyway `V12__picture_rules.sql`). The table is created
  empty on purpose: while there is no row the environment default is in force (version 0). The first staff save inserts the row;
  every save, including the first, adds an audit row (who, when, why, the profile before and after, the overridden knobs).
  The initial value is not seeded by a migration because the core application runs one migration set for every environment; the
  per-environment initial value is the yml line above.
* **Permission:** `catalog:picture-rules.manage` (identity `V54`) to change it, `catalog:moderate` to look. Granted to Super Admin,
  Developer and Catalog Manager. Every change needs a reason (3 to 300 characters) and the version the person loaded (`If-Match`).
* **Java** reads it in process (`PictureRulesProvider`, cached 10 s and dropped on save).
* **Go** reads `GET /v1/public/picture-rules` from catalog through `go/pkg/picturerules.Client` (cached 30 s; the last good answer is
  kept while catalog is down; before the first answer the environment's profile applies). Media, search and gateway-bff each hold one.
  A staff save is visible in catalog at once and in every Go service within 30 seconds; gateway-bff also drops its own cache on save.
* **One definition:** `contracts/picture-rules/vectors.json` holds the profile knobs, 20 URLs against every profile, the sample files
  (tiny valid JPEG, PNG, WebP lossy, lossless and extended, AVIF and AVIS), spoofed files and polyglots. Go, Java and the seeder run
  the same vectors, so the three implementations cannot drift.
* **Staff console:** Settings > Picture rules shows the profile in force, who changed it last (by name), what differs from the
  profile, an explanation of every knob (info button), the profiles side by side, the history, a warning banner whenever the rules are
  not the unchanged strict standard, and (with the permission) the editor with a confirm step that asks for the reason.

## Image formats (every profile)

JPEG, PNG, **WebP and AVIF**. The type is decided by magic bytes, never by the file name or the declared type: WebP is
`RIFF....WEBP`, AVIF is an ISO base media file whose `ftyp` brand is `avif` or `avis` (a HEIC brand is not AVIF). Anything else is
refused, and so is any file that carries markup or script anywhere in its bytes.

**AVIF is not decoded here.** No AVIF decoder is available offline, so media reads the size from the header (`ispe` box), removes the
metadata by overwriting the Exif and XMP items with zeros (every offset stays valid), and cannot compute the perceptual hash,
the white-background measurements or the preview the AI hook needs. Consequence: the **strict** profile **holds every AVIF for staff review**
(it cannot be inspected); `startup` and `testing` publish it with a note ("published without a visual check"). Adding a decoder (for
example `github.com/gen2brain/avif`) lifts this; until then pixel checks and duplicate detection do not run on AVIF.
Stored files keep their real content type and extension, and are served with the matching `Content-Type`. Next.js is configured
for `image/avif` then `image/webp`; the CSP allows images by origin, not by type.

## Later: how product can standardise it

1. Decide the profile per environment with product (the table above is the starting point; every number is a knob).
2. Set it in Settings > Picture rules (reason required), or change `at.catalog.picture-rules.profile` for a new starting point.
3. Tighten gradually: move prod from `startup` to `strict`, or keep `startup` and override one knob (for example `minImages = 2`).
4. A change is visible at once in the audit trail; nothing needs a deploy.

## Not verified here

No Docker, so the Testcontainers integration tests (`ListingRulesIT`, the identity permission migration test) did not run. No browser
decode check of AVIF or WebP. No real AVIF file went through media (the samples are header-only), so a real encoder's output is
unproven. `golangci-lint` could not run (the installed linter is older than the Go toolchain).
