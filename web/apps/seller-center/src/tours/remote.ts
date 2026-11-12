import { type TourProgress as ApiTourProgress } from '@afrotransact/api-client';
import { type RemoteTourProgress, type TourProgressRemote } from '@afrotransact/tour';

import { settingsApi } from '@/lib/settings';

/** The tour ids this app keeps on the server start with this, so Seller Center and the staff console never share a record. */
export const TOUR_NAMESPACE = 'sc';

const fromApi = (p: ApiTourProgress): RemoteTourProgress => ({ tourId: p.tourId, contentVersion: p.contentVersion, status: p.status, lastStep: p.lastStep, updatedAt: p.updatedAt });

/**
 * The server copy of the person's tour progress (identity, through the BFF's /v1/account/tour-progress). The person's own only; the
 * BFF takes the person from the session, so there is no way to ask for anyone else's. Only ids, a status and a time travel.
 */
export function sellerTourRemote(): TourProgressRemote {
  return {
    load: async () => {
      const r = await settingsApi().getTourProgress();
      return { items: r.items.map(fromApi), autoStartEnabled: r.autoStartEnabled };
    },
    save: async (item) => fromApi(await settingsApi().saveTourProgress(item.tourId, { contentVersion: item.contentVersion, status: item.status, lastStep: item.lastStep })),
    merge: async (items) => {
      const r = await settingsApi().mergeTourProgress(items.map((i) => ({ tourId: i.tourId, contentVersion: i.contentVersion, status: i.status, lastStep: i.lastStep })));
      return { items: r.items.map(fromApi), autoStartEnabled: r.autoStartEnabled };
    },
    reset: (tourId) => settingsApi().resetTourProgress(tourId)
  };
}
