# 0014. Google is the only address provider

Status: accepted

## Decision

Google Maps Platform (Places (New), Geocoding, Routes; one key, `GOOGLE_PLACES_API_KEY` else `GOOGLE_MAPS_API_KEY`) is the only
address provider for autocomplete, resolve and validation. The previous paid US address-verification vendor is removed: its adapter,
its configuration and secrets, its runbook entries and its statuses. It will not be integrated again.

Owner words: "Google address should be enough. We should not be integrating Smarty again." and "We should not say UNVERIFIED. Once
Google Maps validates the address, that's it."

## What changed

- `POST /v1/addresses/validate` answers `VALID` or `NOT_FOUND`. A suggestion the person picked and Google resolved to a street
  address with coordinates is `VALID` and final. A typed address is geocoded server side and is `VALID` on a street-level match
  (rooftop or range-interpolated) in the same ZIP. Otherwise `NOT_FOUND` with a plain-words message and, when Google found the
  street in another ZIP, that address as a suggestion.
- `CORRECTED`, `MISSING_UNIT`, `UNDELIVERABLE`, `UNVERIFIED` and the residential/commercial flag are gone. Nothing in the repo
  consumed the residential flag.
- `NOT_FOUND` never blocks saving an address: the product shows the message and lets people correct it or continue.
- If Google cannot be reached, or no key is configured (local and offline development with fixtures), the address is accepted
  without a warning. uat and prod refuse to start without a Google key (`go/services/geo/internal/config/validate.go`), so this
  path cannot occur there.

## Why

One vendor to key, budget and secure; Google already serves autocomplete, geocoding and routing; a second vendor's verdict
produced states ("unverified") that confused shoppers and sellers without helping delivery.
