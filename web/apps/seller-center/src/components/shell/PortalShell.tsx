'use client';

import { isApiError, type ModuleKey, sellerKeys, sellerQueries, type SellerSession } from '@afrotransact/api-client';
import { Button, Sheet, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { usePathname, useRouter } from 'next/navigation';
import { type ReactNode, useEffect, useState } from 'react';

import { ErrorState, NoAccess, Spinner } from '@/components/ui/States';
import { sellerApi } from '@/lib/api';
import { storefrontUrl } from '@/lib/config';
import { hrefOf, moduleOf } from '@/lib/routes';
import { SellerSessionProvider } from '@/lib/session';

import { ComplianceBanner, PayoutsPausedBanner } from './ComplianceBanner';
import { Header } from './Header';
import { Sidebar } from './Sidebar';

function Centered({ children }: { children: ReactNode }) {
  return <div className="mx-auto flex w-[min(560px,100%)] flex-col items-center gap-4 px-4 py-16 text-center">{children}</div>;
}

function SignInPrompt() {
  const returnTo = typeof window !== 'undefined' ? window.location.href : '/';
  return (
    <Centered>
      <h1 className="m-0 text-2xl font-bold">Sign in to Seller Central</h1>
      <p className="m-0 text-muted">Use the same AfroTransact account you shop with. Seller Center and Provider Hub open after your application is approved.</p>
      <a className="rounded-pill bg-primary px-6 py-2.5 font-bold text-on-primary no-underline hover:bg-primary-hover hover:text-on-primary hover:no-underline" href={`${storefrontUrl()}/signin?returnTo=${encodeURIComponent(returnTo)}`}>
        Sign in
      </a>
    </Centered>
  );
}

function NoOrganisation() {
  return (
    <Centered>
      <h1 className="m-0 text-2xl font-bold">Start selling on AfroTransact</h1>
      <p className="m-0 text-muted">You don’t belong to a seller or service-provider organisation yet. Apply in a few steps — you can save and finish later.</p>
      <a className="rounded-pill bg-primary px-6 py-2.5 font-bold text-on-primary no-underline hover:bg-primary-hover hover:text-on-primary hover:no-underline" href={`${storefrontUrl()}/sell`}>
        Start selling
      </a>
    </Centered>
  );
}

function allowed(session: SellerSession, key: ModuleKey | null): boolean {
  return key !== null && session.navGroups.some((g) => g.modules.some((m) => m.key === key));
}

/**
 * Portal shell: black header, fixed grouped sidebar (it never scrolls with the page), compliance banner and the
 * page. Before approval only Onboarding is reachable; after approval Onboarding is gone and pages the role can't
 * open show an access notice.
 */
export function PortalShell({ children }: { children: ReactNode }) {
  const api = sellerApi();
  const qc = useQueryClient();
  const router = useRouter();
  const pathname = usePathname();
  const toast = useToast();
  const [menuOpen, setMenuOpen] = useState(false);
  const q = useQuery({ ...sellerQueries.session(api), retry: (n, e) => !(isApiError(e) && e.status < 500) && n < 2 });
  const session = q.data;
  const active = moduleOf(pathname);

  const switchOrg = useMutation({
    mutationFn: (orgId: string) => api.setActiveOrg(orgId),
    onSuccess: (s) => {
      qc.setQueryData(sellerKeys.session(), s);
      void qc.invalidateQueries({ queryKey: sellerKeys.all, predicate: (query) => query.queryKey[1] !== 'session' });
      setMenuOpen(false);
      toast.show(`Switched to ${s.activeOrg?.name ?? 'organisation'}`);
      router.push(hrefOf(s.landing));
    },
    onError: () => toast.show('Couldn’t switch organisation. Please try again.', 'error')
  });

  const needsOnboarding = !!session?.activeOrg && !session.approved && active !== 'onboarding';
  const leaveOnboarding = !!session?.approved && active === 'onboarding';
  useEffect(() => {
    if (needsOnboarding) router.replace('/onboarding');
    else if (leaveOnboarding && session) router.replace(hrefOf(session.landing));
  }, [needsOnboarding, leaveOnboarding, session, router]);

  if (q.isPending) {
    return (
      <>
        <Header session={null} />
        <div className="p-8">
          <Spinner label="Loading your back office…" />
        </div>
      </>
    );
  }
  if (q.isError || !session) {
    if (isApiError(q.error) && q.error.status === 401) {
      return (
        <>
          <Header session={null} />
          <SignInPrompt />
        </>
      );
    }
    return (
      <>
        <Header session={null} />
        <div className="p-8">
          <ErrorState error={q.error} onRetry={() => void q.refetch()} />
        </div>
      </>
    );
  }
  if (!session.activeOrg) {
    return (
      <>
        <Header session={session} />
        <NoOrganisation />
      </>
    );
  }

  const sidebar = (onNavigate?: () => void) => (
    <Sidebar session={session} active={active} onSwitch={(id) => switchOrg.mutate(id)} switching={switchOrg.isPending} onNavigate={onNavigate} />
  );
  const canOpen = allowed(session, active);
  return (
    <SellerSessionProvider session={session}>
      <Header session={session} onMenu={() => setMenuOpen(true)} />
      <div className="grid min-h-[calc(100vh-56px)] grid-cols-1 lg:grid-cols-[264px_minmax(0,1fr)]">
        <aside className="sticky top-14 hidden h-[calc(100vh-56px)] self-start overflow-y-auto overscroll-contain border-r border-border bg-surface lg:block">
          {sidebar()}
        </aside>
        <Sheet open={menuOpen} onOpenChange={setMenuOpen} title="Menu" side="left">
          {sidebar(() => setMenuOpen(false))}
        </Sheet>
        <main id="main" tabIndex={-1} className="flex min-w-0 flex-col gap-5 px-4 pt-6 pb-12 outline-none sm:px-8">
          {session.approved ? <PayoutsPausedBanner /> : null}
          {session.approved ? <ComplianceBanner onAccountHealth={active === 'account-health'} /> : null}
          {needsOnboarding || leaveOnboarding ? (
            <Spinner />
          ) : canOpen ? (
            children
          ) : (
            <NoAccess role={session.activeOrg.roleLabel} />
          )}
          {!canOpen && !needsOnboarding && !leaveOnboarding ? (
            <div className="flex justify-center">
              <Button variant="neutral" onClick={() => router.push(hrefOf(session.landing))}>
                Go to {session.navGroups[0]?.modules[0]?.label ?? 'home'}
              </Button>
            </div>
          ) : null}
        </main>
      </div>
    </SellerSessionProvider>
  );
}
