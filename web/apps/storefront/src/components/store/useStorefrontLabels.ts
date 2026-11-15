'use client';

import { type StorefrontLabels } from '@afrotransact/ui';
import { useMemo } from 'react';

import { useT } from '@/i18n/t';

const NETWORKS = ['instagram', 'facebook', 'tiktok', 'x', 'youtube', 'whatsapp', 'website'] as const;
const SORTS = ['relevance', 'price_asc', 'price_desc', 'rating', 'newest'] as const;

/** The storefront's words in the shopper's language (store.* in the catalogues). */
export function useStorefrontLabels(): StorefrontLabels {
  const t = useT();
  return useMemo(
    () => ({
      skip: t('store.skip'),
      announcement: t('store.announcement'),
      searchLabel: t('store.searchLabel'),
      searchPlaceholder: (store) => t('store.searchPlaceholder', { store }),
      searchSubmit: t('store.searchSubmit'),
      cart: (count) => (count > 0 ? t('store.cartCount', { count }) : t('store.cart')),
      account: t('store.account'),
      storeNav: t('store.storeNav'),
      allProducts: t('store.allProducts'),
      allCategories: t('store.allCategories'),
      featured: t('store.featured'),
      shopByCategory: t('store.shopByCategory'),
      categoryCount: (n) => t(n === 1 ? 'store.categoryCount.one' : 'store.categoryCount.other', { n }),
      about: t('store.about'),
      policies: t('store.policies'),
      shipping: t('store.shipping'),
      returns: t('store.returns'),
      pickup: (zip) => t('store.pickup', { zip }),
      reviewsTitle: t('store.reviewsTitle'),
      reviewsSummary: (average, count) => t('store.reviewsSummary', { average, count }),
      contact: t('store.contact'),
      hours: t('store.hours'),
      follow: t('store.follow'),
      soldOn: t('store.soldOn'),
      verified: t('store.verified'),
      sellingSince: (year) => t('store.sellingSince', { year }),
      shopAll: t('store.shopAll'),
      addToCart: t('store.addToCart'),
      buyNow: t('store.buyNow'),
      quickView: t('store.quickView'),
      closeLabel: t('store.close'),
      viewDetails: t('store.viewDetails'),
      inCart: (n) => t('store.inCart', { n }),
      onlyLeft: (n) => t('store.onlyLeft', { n }),
      off: (percent) => t('store.off', { percent }),
      onSale: t('store.onSale'),
      noSale: t('store.noSale'),
      sort: t('store.sort'),
      sortOptions: Object.fromEntries(SORTS.map((s) => [s, t(`store.sort.${s}`)])) as StorefrontLabels['sortOptions'],
      price: t('store.price'),
      priceAny: t('store.priceAny'),
      category: t('store.category'),
      clear: t('store.clear'),
      results: (shown, total) => t('store.results', { shown, total }),
      loadMore: t('store.loadMore'),
      loading: t('store.loading'),
      noResults: t('store.noResults'),
      noProducts: t('store.noProducts'),
      error: t('store.error'),
      retry: t('store.retry'),
      rating: (value) => t('store.rating', { value }),
      reviewsCount: (count) => t('store.reviewsCount', { count }),
      preview: t('store.preview'),
      networks: Object.fromEntries(NETWORKS.map((n) => [n, t(`store.network.${n}`)])),
      opensNewTab: t('store.opensNewTab'),
      verifiedProvider: t('store.verifiedProvider'),
      bookAppointment: t('store.bookAppointment'),
      servicesTitle: t('store.servicesTitle'),
      bookTitle: t('store.bookTitle'),
      scheduleTitle: t('store.scheduleTitle'),
      teamTitle: t('store.teamTitle'),
      areaTitle: t('store.areaTitle'),
      minutes: (n) => t('store.minutes', { n }),
      deposit: (amount) => t('store.deposit', { amount }),
      modeInPerson: t('store.modeInPerson'),
      modeAtHome: t('store.modeAtHome'),
      bookService: t('store.bookService'),
      closed: t('store.closed'),
      timesIn: (zone) => t('store.timesIn', { zone }),
      basedIn: (place) => t('store.basedIn', { place }),
      travelsUpTo: (miles) => t('store.travelsUpTo', { miles }),
      alsoServes: (list) => t('store.alsoServes', { list }),
      cancellation: (label) => t('store.cancellation', { label }),
      chooseService: t('store.chooseService'),
      previewBooker: t('store.previewBooker'),
      noServices: t('store.noServices'),
      providerNav: t('store.providerNav')
    }),
    [t]
  );
}
