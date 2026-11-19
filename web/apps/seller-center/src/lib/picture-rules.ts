'use client';

import { type PictureRules, pictureRulesQueries, STRICT_PICTURE_RULES } from '@afrotransact/api-client';
import { useQuery } from '@tanstack/react-query';

import { sellerApi } from '@/lib/api';

/**
 * The picture rule in force (Settings > Picture rules in the staff console): how many photos, how large, which checks. Messages and
 * limits on the photo screens read it instead of fixed numbers. Until it has loaded, or when it cannot be read, the strict standard
 * applies (today's behaviour); the server enforces the real rule either way.
 */
export function usePictureRules(): PictureRules {
  const q = useQuery(pictureRulesQueries.get(sellerApi().pictureRules));
  return q.data ?? STRICT_PICTURE_RULES;
}

/** The size cap of one file in bytes. */
export const maxBytesOf = (r: PictureRules): number => r.knobs.maxMegabytes * 1024 * 1024;
