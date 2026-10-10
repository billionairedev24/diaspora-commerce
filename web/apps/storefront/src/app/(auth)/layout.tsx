import { type ReactNode } from 'react';

/** Auth pages render without the storefront header, department bar, announcement bar or footer. */
export default function AuthLayout({ children }: { children: ReactNode }) {
  return <>{children}</>;
}
