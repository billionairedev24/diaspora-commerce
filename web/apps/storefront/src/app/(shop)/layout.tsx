import { type ReactNode } from 'react';

import { ShopChrome } from '@/components/shell/ShopChrome';

/** Storefront chrome: announcement bar, header, department bar, footer and the first-visit cookie banner (not used by /signin and /signup). */
export default async function ShopLayout({ children }: { children: ReactNode }) {
  return <ShopChrome>{children}</ShopChrome>;
}
