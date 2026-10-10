'use client';

import { type LinkLikeProps } from '@afrotransact/ui';
import Link from 'next/link';

/** Next.js <Link> adapter for @afrotransact/ui components that accept `linkAs`. */
export function ProductLink({ href, className, children, ...rest }: LinkLikeProps) {
  return (
    <Link href={href} className={className} {...rest}>
      {children}
    </Link>
  );
}
