import { type ComponentType } from 'react';

import { type LinkLikeProps } from '../commerce/ProductCard';

/* Structural types for the seller storefront (they match @afrotransact/api-client's Storefront; ui has no dependency on the API client). */

export type StoreSectionId = 'announcement' | 'featured' | 'categories' | 'about' | 'policies' | 'reviews';

export interface StorefrontConfigModel {
  theme: { preset: string; accent: string | null };
  hero: { style: 'image' | 'colour' | 'split'; tagline: string; bannerUrl: string | null; focalX: number; focalY: number };
  announcement: { text: string; href: string | null };
  grid: { columns: 3 | 4 };
  sections: { id: StoreSectionId; enabled: boolean }[];
  categories: { mode: 'auto' | 'chosen'; chosen: string[] };
  about: { heading: string; body: string; photoUrl: string | null };
  policies: { shipping: string; returns: string };
  contact: { showEmail: boolean; phone: string; hours: string };
  pickup: { show: boolean };
  social: { network: string; url: string }[];
}

export interface StorefrontProductModel {
  atin: string;
  title: string;
  brand: string;
  department: string;
  unit: string;
  rating: number;
  reviewCount: number;
  imageUrl: string | null;
  offerId: string;
  priceMinor: number;
  listPriceMinor: number | null;
  currency: string;
  availableQty: number;
  lowStock: boolean;
}

export interface StorefrontCategoryModel {
  name: string;
  count: number;
}

export interface StorefrontPageModel {
  hits: StorefrontProductModel[];
  total: number;
  page: number;
  pageCount: number;
  categories: StorefrontCategoryModel[];
  prices: { value: string; label: string; count: number }[];
}

export interface StorefrontModel {
  slug: string;
  name: string;
  logoUrl: string | null;
  description: string | null;
  config: StorefrontConfigModel;
  verified: boolean;
  sellingSinceYear: number | null;
  contactEmail: string | null;
  pickup: { zip: string } | null;
  categories: StorefrontCategoryModel[];
  featured: StorefrontProductModel[];
  reviews: { average: number; count: number } | null;
  products: StorefrontPageModel;
}

export type StorefrontSort = 'relevance' | 'price_asc' | 'price_desc' | 'rating' | 'newest';

export interface StorefrontQueryState {
  q: string;
  category: string;
  price: string;
  sort: StorefrontSort;
}

export const EMPTY_QUERY: StorefrontQueryState = { q: '', category: '', price: '', sort: 'relevance' };

/** Everything that touches the marketplace: links, the cart and checkout. Preview passes inert versions. */
export interface StorefrontCommerce {
  homeHref: string;
  cartHref: string;
  accountHref: string;
  cartCount: number;
  productHref: (atin: string) => string;
  linkAs?: ComponentType<LinkLikeProps>;
  quantityInCart: (offerId: string) => number;
  onAdd: (p: StorefrontProductModel) => void;
  onBuyNow: (p: StorefrontProductModel) => void;
  busy?: boolean;
}

export interface StorefrontLabels {
  skip: string;
  announcement: string;
  searchLabel: string;
  searchPlaceholder: (store: string) => string;
  searchSubmit: string;
  cart: (count: number) => string;
  account: string;
  storeNav: string;
  allProducts: string;
  allCategories: string;
  featured: string;
  shopByCategory: string;
  categoryCount: (n: number) => string;
  about: string;
  policies: string;
  shipping: string;
  returns: string;
  pickup: (zip: string) => string;
  reviewsTitle: string;
  reviewsSummary: (average: string, count: string) => string;
  contact: string;
  hours: string;
  follow: string;
  soldOn: string;
  verified: string;
  sellingSince: (year: number) => string;
  shopAll: string;
  addToCart: string;
  buyNow: string;
  quickView: string;
  closeLabel: string;
  viewDetails: string;
  inCart: (n: number) => string;
  onlyLeft: (n: number) => string;
  off: (percent: number) => string;
  inStockNote: string;
  sort: string;
  sortOptions: Record<StorefrontSort, string>;
  price: string;
  priceAny: string;
  category: string;
  clear: string;
  results: (shown: number, total: number) => string;
  loadMore: string;
  loading: string;
  noResults: string;
  noProducts: string;
  error: string;
  retry: string;
  rating: (value: string) => string;
  reviewsCount: (count: string) => string;
  preview: string;
  networks: Record<string, string>;
  opensNewTab: string;
}

export const defaultStorefrontLabels: StorefrontLabels = {
  skip: 'Skip to products',
  announcement: 'Announcement',
  searchLabel: 'Search this store',
  searchPlaceholder: (store) => `Search ${store}`,
  searchSubmit: 'Search',
  cart: (n) => (n > 0 ? `Cart, ${n} items` : 'Cart'),
  account: 'Account',
  storeNav: 'Store categories',
  allProducts: 'All products',
  allCategories: 'All',
  featured: 'Featured',
  shopByCategory: 'Shop by category',
  categoryCount: (n) => `${n} ${n === 1 ? 'product' : 'products'}`,
  about: 'Our story',
  policies: 'Shipping and returns',
  shipping: 'Shipping',
  returns: 'Returns',
  pickup: (zip) => `Pickup available near ${zip}`,
  reviewsTitle: 'What customers say',
  reviewsSummary: (average, count) => `${average} out of 5 across ${count} customer reviews`,
  contact: 'Contact',
  hours: 'Hours',
  follow: 'Follow',
  soldOn: 'Sold on AfroTransact',
  verified: 'Verified seller',
  sellingSince: (y) => `On AfroTransact since ${y}`,
  shopAll: 'Shop all products',
  addToCart: 'Add to cart',
  buyNow: 'Buy now',
  quickView: 'Quick view',
  closeLabel: 'Close',
  viewDetails: 'See full details',
  inCart: (n) => `${n} in cart`,
  onlyLeft: (n) => `Only ${n} left`,
  off: (p) => `${p}% off`,
  inStockNote: 'Showing products in stock now',
  sort: 'Sort by',
  sortOptions: { relevance: 'Featured', price_asc: 'Price: low to high', price_desc: 'Price: high to low', rating: 'Top rated', newest: 'Newest' },
  price: 'Price',
  priceAny: 'Any price',
  category: 'Category',
  clear: 'Clear filters',
  results: (shown, total) => `Showing ${shown} of ${total}`,
  loadMore: 'Show more',
  loading: 'Loading products',
  noResults: 'No products match. Try fewer filters or another word.',
  noProducts: 'New products are on their way.',
  error: 'We could not load products.',
  retry: 'Try again',
  rating: (v) => `${v} out of 5 stars`,
  reviewsCount: (c) => `(${c})`,
  preview: 'Preview: buttons are switched off',
  networks: { instagram: 'Instagram', facebook: 'Facebook', tiktok: 'TikTok', x: 'X', youtube: 'YouTube', whatsapp: 'WhatsApp', website: 'Website' },
  opensNewTab: '(opens in a new tab)'
};
