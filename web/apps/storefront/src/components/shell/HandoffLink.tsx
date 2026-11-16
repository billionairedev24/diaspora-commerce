import Link from 'next/link';
import { type ComponentProps } from 'react';

import { isHandoffHref } from '@/lib/sell/handoff';

type Props = ComponentProps<typeof Link>;

/**
 * A link that is a plain <a> where the address hands over to another app (/sell, /provide answer with a cross-origin
 * redirect, which a <Link>'s RSC prefetch or navigation fetch cannot follow), and a normal <Link> everywhere else.
 */
export function HandoffLink(props: Props) {
  const { href } = props;
  const target = typeof href === 'string' ? href : (href.pathname ?? '');
  if (!isHandoffHref(target)) return <Link {...props} />;
  const { prefetch, replace, scroll, ...anchor } = props;
  void [prefetch, replace, scroll];
  return <a {...anchor} href={target} />;
}
