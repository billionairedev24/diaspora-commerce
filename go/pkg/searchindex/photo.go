package searchindex

import "github.com/billionairedev24/diaspora-commerce/go/pkg/picturerules"

// PhotoOK reports whether imageURL points at an actual photograph: an http(s) or site-relative address that is not an
// inline (data:) or vector (svg) image, not an icon (ico) and not one of the known placeholders. JPEG, PNG, WebP and AVIF
// addresses (and addresses without an extension) all count. It is the ONE strict rule for "a real photo", defined in
// picturerules.IsRealPhoto: the indexer stores its result as photo_ok. Whether the storefront filters on it is the
// profile's decision (picturerules.Rules.ShowOnStorefront / StorefrontPhotoFilter), so changing the setting needs no reindex.
func PhotoOK(imageURL string) bool { return picturerules.IsRealPhoto(imageURL) }
