'use client';

import { useEffect } from 'react';

import { rememberViewed } from '@/lib/recent/recentlyViewed';

/** Remembers that this browser opened the product (see "Recently viewed" on the home page). Renders nothing. */
export function TrackView({ atin }: { atin: string }) {
  useEffect(() => rememberViewed(atin), [atin]);
  return null;
}
