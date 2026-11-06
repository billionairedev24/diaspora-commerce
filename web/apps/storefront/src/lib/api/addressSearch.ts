'use client';

import { addressLookup } from '@afrotransact/api-client';
import { type SearchAddresses } from '@afrotransact/ui';

import { browserApi } from './browser';

/** Street suggestions through the BFF (geo: Google Places with session tokens, else OSM); picked Google suggestions resolve on pick. */
export const bffAddressSearch: SearchAddresses = (q, options) => addressLookup(browserApi())(q, options);
