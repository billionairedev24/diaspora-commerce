import { z } from 'zod';

export const ZipSchema = z.string().regex(/^\d{5}$/, 'Enter a valid 5-digit US ZIP code.');

export const DeliveryLocationSchema = z.object({
  zip: z.string(),
  city: z.string(),
  state: z.string(),
  /** Header label, e.g. "Houston 77042". */
  label: z.string(),
  serviceable: z.boolean()
});
export type DeliveryLocation = z.infer<typeof DeliveryLocationSchema>;

export const PostalAddressSchema = z.object({
  line1: z.string(),
  line2: z.string().default(''),
  city: z.string(),
  state: z.string(),
  zip: z.string(),
  country: z.string().default('US'),
  lat: z.number().nullable().default(null),
  lon: z.number().nullable().default(null)
});
export type PostalAddress = z.infer<typeof PostalAddressSchema>;

export const AddressSuggestionSchema = z.object({
  id: z.string(),
  label: z.string(),
  secondary: z.string(),
  address: PostalAddressSchema,
  source: z.enum(['google', 'osm', 'fixture']),
  /** false: `address` holds the street line only (Google Places); resolve the suggestion when it is picked. */
  resolved: z.boolean().default(true)
});
export type AddressSuggestion = z.infer<typeof AddressSuggestionSchema>;

export const AddressSuggestionsSchema = z.object({
  suggestions: z.array(AddressSuggestionSchema),
  attribution: z.string(),
  /** Autocomplete session of this address entry: send it with the next keystrokes and the resolve (Google billing). */
  sessionToken: z.string().default('')
});
export type AddressSuggestions = z.infer<typeof AddressSuggestionsSchema>;
