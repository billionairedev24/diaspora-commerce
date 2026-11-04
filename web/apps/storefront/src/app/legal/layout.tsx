import { type ReactNode } from 'react';

/** Legal & policy documents (Conditions of Use, Cookie Policy, Privacy Notice, etc.) render without the storefront header (navbar), department bar or footer. */
export default function LegalLayout({ children }: { children: ReactNode }) {
  return (
    <main id="main" tabIndex={-1} className="page">
      {children}
    </main>
  );
}
