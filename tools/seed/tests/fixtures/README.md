Hand-written API responses for the seeder's unit tests. They follow the documented response shapes but were **not**
recorded from the live services (this repository's CI and the sandbox it was written in cannot reach them):

- `wikimedia_search_jollof.json`: MediaWiki Action API `action=query&generator=search&gsrnamespace=6&prop=imageinfo&iiprop=url|size|mime|extmetadata&iiurlwidth=1600&format=json`
  (https://www.mediawiki.org/wiki/API:Imageinfo, https://www.mediawiki.org/wiki/Extension:CommonsMetadata), formatversion 1
  (`query.pages` keyed by page id). It mixes acceptable photos with files the seeder must skip (NC license, SVG, logo,
  missing license, GFDL-only).
- `wikimedia_search_formatversion2.json`: the same request with `formatversion=2` (`query.pages` is a list).
- `pexels_search.json`: Pexels API `GET /v1/search` (https://www.pexels.com/api/documentation/#photos-search).
- `unsplash_search.json`: Unsplash API `GET /search/photos` (https://unsplash.com/documentation#search-photos).

File names, authors and URLs are made up.
