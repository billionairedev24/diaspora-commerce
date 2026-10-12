'use client';

import { AccountFormatPreferences, accountSectionOf } from '@afrotransact/account';
import { isApiError, type ModuleKey, type OnboardingStatus, sellerKeys, sellerQueries, type SellerSession } from '@afrotransact/api-client';
import { BackofficeFrame, Button, useNavLoop, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { usePathname, useRouter } from 'next/navigation';
import { type ReactNode, useEffect, useRef, useState } from 'react';

import { NoAccess } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { setFormatPreferences } from '@/lib/format';
import { navGuard, useShellRedirect } from '@/lib/nav-guard';
import { type ApplyRole, applyRoleOf, entryDecision, kindOf } from '@/lib/onboarding-entry';
import { hrefOf, moduleOf } from '@/lib/routes';
import { useStorefrontUrl } from '@/lib/runtime-config';
import { SellerSessionProvider } from '@/lib/session';

import { ComplianceBanner, PayoutsPausedBanner } from './ComplianceBanner';
import { Header } from './Header';
import { AutoSignIn, clearSignInAttempt, ShellFailed, ShellLoading, ShellLooped } from './ShellStates';
import { Sidebar, SidebarRail } from './Sidebar';

function Centered({ children }: { children: ReactNode }) {
  return <div className="mx-auto flex w-[min(560px,100%)] flex-col items-center gap-4 px-4 py-16 text-center">{children}</div>;
}

const PILL = 'rounded-pill bg-primary px-6 py-2.5 font-bold text-on-primary no-underline hover:bg-primary-hover hover:text-on-primary hover:no-underline';

function NoOrganisation() {
  const t = useT();
  return (
    <Centered>
      <h1 className="m-0 text-2xl font-bold">{t('sc.portalShell.startSellingOnAfrotransact')}</h1>
      <p className="m-0 text-muted">{t('sc.portalShell.youDonTBelongTo')}</p>
      <div className="flex flex-wrap justify-center gap-3">
        <a className={PILL} href="/onboarding?role=seller">
          {t('sc.portalShell.startSelling')}
        </a>
        <a className="rounded-pill border border-border px-6 py-2.5 font-bold text-text no-underline hover:no-underline" href="/onboarding?role=provider">
          {t('sc.portalShell.offerYourServices')}
        </a>
      </div>
    </Centered>
  );
}

/** "Start selling" could not continue (a staff account, or an application of the other kind in progress). */
function CannotApply({ message }: { message: string }) {
  const storefrontUrl = useStorefrontUrl();
  const t = useT();
  return (
    <Centered>
      <h1 className="m-0 text-2xl font-bold">{t('sc.portalShell.cannotStartApplication')}</h1>
      <p className="m-0 text-muted">{message}</p>
      <a className={PILL} href={storefrontUrl}>
        {t('sc.portalShell.backToTheStorefront')}
      </a>
    </Centered>
  );
}

type EntryState = { phase: 'idle' | 'working' } | { phase: 'failed'; message: string };

/**
 * Arrival from "Start selling" / "Offer your services" (/onboarding?role=…): approved sellers go to their back
 * office, an application in progress is resumed, otherwise it is started (applicant role added, prefilled from the
 * account) — no questions, no extra page.
 */
function useOnboardingEntry(session: SellerSession | undefined, role: ApplyRole | null, done: () => void): EntryState {
  const api = sellerApi();
  const qc = useQueryClient();
  const redirect = useShellRedirect();
  const t = useT();
  const started = useRef(false);
  const [state, setState] = useState<EntryState>({ phase: role ? 'working' : 'idle' });
  useEffect(() => {
    if (!session || !role || started.current) return;
    started.current = true;
    const decision = entryDecision(session, role);
    const run = async () => {
      let next = session;
      if (decision.action !== 'start' && decision.switchTo) {
        next = await api.setActiveOrg(decision.switchTo);
        qc.setQueryData(sellerKeys.session(), next);
      }
      if (decision.action === 'home') {
        redirect(hrefOf(next.landing));
        return;
      }
      // Start, or resume: the same idempotent call (a resumed draft gets what the account knows in empty answers).
      const st: OnboardingStatus = await api.startOnboarding(kindOf(role));
      qc.setQueryData(sellerKeys.onboarding(st.orgId), st);
      await qc.invalidateQueries({ queryKey: sellerKeys.session() });
      redirect('/onboarding');
    };
    run()
      .then(() => {
        setState({ phase: 'idle' });
        done();
      })
      .catch((e: unknown) => {
        if (isApiError(e) && e.code === 'ALREADY_APPROVED') {
          // Approved in the meantime (identity had not listed the organisation yet): open the back office.
          void qc.invalidateQueries({ queryKey: sellerKeys.session() });
          redirect('/');
          setState({ phase: 'idle' });
          done();
          return;
        }
        setState({ phase: 'failed', message: (isApiError(e) && e.problem.detail) || t('sc.portalShell.couldNotStartApplication') });
      });
  }, [session, role, api, qc, redirect, t, done]);
  return role ? state : { phase: 'idle' };
}

/** Longest wait for one attempt at the session (two retries follow a timeout). */
export const SESSION_TIMEOUT_MS = 12_000;

function allowed(session: SellerSession, key: ModuleKey | null): boolean {
  return key !== null && session.navGroups.some((g) => g.modules.some((m) => m.key === key));
}

/**
 * Portal shell: black header, fixed grouped sidebar (it never scrolls with the page), compliance banner and the
 * page. Before approval only Onboarding is reachable; after approval Onboarding is gone and pages the role can't
 * open show an access notice.
 */
export function PortalShell({ children }: { children: ReactNode }) {
  const t = useT();
  const api = sellerApi();
  const qc = useQueryClient();
  const router = useRouter();
  const pathname = usePathname();
  const toast = useToast();
  const q = useQuery({
    ...sellerQueries.session(api, { timeoutMs: SESSION_TIMEOUT_MS }),
    retry: (n, e) => !(isApiError(e) && e.status < 500) && n < 2
  });
  const loop = useNavLoop(navGuard);
  const redirect = useShellRedirect();
  const session = q.data;
  const active = moduleOf(pathname);
  // My account (/account/…) belongs to the person, not an organisation: open without one, but not while an
  // application is in progress (only onboarding is open then).
  const accountPage = accountSectionOf(pathname) !== null;
  // Read once on arrival (no useSearchParams: the shell renders on every page, statically).
  const [applyRole, setApplyRole] = useState<ApplyRole | null>(() =>
    typeof window === 'undefined' || moduleOf(window.location.pathname) !== 'onboarding' ? null : applyRoleOf(window.location.search)
  );
  const entry = useOnboardingEntry(q.data, applyRole, () => setApplyRole(null));
  const entering = applyRole !== null || entry.phase === 'working';

  const switchOrg = useMutation({
    mutationFn: (orgId: string) => api.setActiveOrg(orgId),
    onSuccess: (s) => {
      qc.setQueryData(sellerKeys.session(), s);
      void qc.invalidateQueries({ queryKey: sellerKeys.all, predicate: (query) => query.queryKey[1] !== 'session' });
      toast.show(t('sc.portalShell.switched', { org: s.activeOrg?.name ?? t('sc.portalShell.organisation') }));
      router.push(hrefOf(s.landing));
    },
    onError: () => toast.show(t('sc.portalShell.switchFailed'), 'error')
  });

  // Signed in: a later expired session may go to sign-in again straight away (see AutoSignIn's loop guard).
  useEffect(() => {
    if (session) clearSignInAttempt();
  }, [session]);

  const needsOnboarding = !entering && !!session?.activeOrg && !session.approved && active !== 'onboarding';
  const leaveOnboarding = !entering && !!session?.approved && active === 'onboarding';
  // Approved: onboarding is gone, go to the landing — unless the landing is onboarding itself (a session that says
  // approved with nothing else open), which would send the page to itself for ever.
  const leaveTo = leaveOnboarding && session && session.landing !== 'onboarding' ? hrefOf(session.landing) : null;
  useEffect(() => {
    if (needsOnboarding) redirect('/onboarding');
    else if (leaveTo) redirect(leaveTo);
  }, [needsOnboarding, leaveTo, redirect]);
  const showsPage = !!session && !entering && !needsOnboarding && !leaveTo && !loop;
  useEffect(() => {
    if (showsPage) navGuard.loaded();
  }, [showsPage]);

  if (loop) {
    return (
      <>
        <Header session={session ?? null} />
        <ShellLooped loop={loop} />
      </>
    );
  }
  if (q.isPending) {
    return (
      <>
        <Header session={null} />
        <div className="p-8">
          <ShellLoading label={t('sc.portalShell.loadingYourBackOffice')} onRetry={() => void q.refetch()} />
        </div>
      </>
    );
  }
  if (q.isError || !session) {
    if (isApiError(q.error) && q.error.status === 401) {
      return (
        <>
          <Header session={null} />
          <AutoSignIn role={applyRole} label={t('sc.portalShell.takingYouToSignIn')} />
        </>
      );
    }
    return (
      <>
        <Header session={null} />
        <div className="p-8">
          <ShellFailed error={q.error} onRetry={() => void q.refetch()} />
        </div>
      </>
    );
  }
  if (entry.phase === 'failed') {
    return (
      <>
        <Header session={session} />
        <CannotApply message={entry.message} />
      </>
    );
  }
  if (entering) {
    return (
      <>
        <Header session={session} />
        <div className="p-8">
          <ShellLoading label={t('sc.portalShell.settingUpYourApplication')} onRetry={() => window.location.reload()} />
        </div>
      </>
    );
  }
  if (!session.activeOrg) {
    return (
      <>
        <Header session={session} />
        {accountPage ? (
          <main id="main" tabIndex={-1} className="px-4 pt-6 pb-12 outline-none sm:px-8">
            <AccountFormatPreferences apply={setFormatPreferences}>{children}</AccountFormatPreferences>
          </main>
        ) : (
          <NoOrganisation />
        )}
      </>
    );
  }

  const canOpen = accountPage || allowed(session, active);
  return (
    <SellerSessionProvider session={session}>
      <BackofficeFrame
        header={(menuButton) => <Header session={session} menuButton={menuButton} />}
        nav={<Sidebar session={session} active={active} onSwitch={(id) => switchOrg.mutate(id)} switching={switchOrg.isPending} />}
        rail={<SidebarRail session={session} active={active} />}
        routeKey={pathname}
        menuLabel={t('sc.portalShell.menu')}
        expandLabel={t('sc.portalShell.expandMenu')}
        collapseLabel={t('sc.portalShell.collapseMenu')}
      >
        <main id="main" tabIndex={-1} className="flex min-w-0 flex-col gap-5 px-4 pt-6 pb-12 outline-none sm:px-8">
          {session.approved ? <PayoutsPausedBanner /> : null}
          {session.approved ? <ComplianceBanner onAccountHealth={active === 'account-health'} /> : null}
          {needsOnboarding || leaveTo ? (
            <ShellLoading label={t('sc.portalShell.loadingYourBackOffice')} onRetry={() => window.location.reload()} />
          ) : canOpen ? (
            <AccountFormatPreferences apply={setFormatPreferences}>{children}</AccountFormatPreferences>
          ) : (
            <NoAccess role={session.activeOrg.roleLabel} />
          )}
          {!canOpen && !needsOnboarding && !leaveTo ? (
            <div className="flex justify-center">
              <Button variant="neutral" onClick={() => router.push(hrefOf(session.landing))}>
                Go to {session.navGroups[0]?.modules[0]?.label ?? 'home'}
              </Button>
            </div>
          ) : null}
        </main>
      </BackofficeFrame>
    </SellerSessionProvider>
  );
}
