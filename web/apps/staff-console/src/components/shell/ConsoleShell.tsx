'use client';

import { AccountFormatPreferences, accountSectionOf } from '@afrotransact/account';
import { isApiError, staffQueries,type StaffSession } from '@afrotransact/api-client';
import { BackofficeFrame, Button, type NavLoop, useNavLoop } from '@afrotransact/ui';
import { HourglassMedium, SignOut, WarningCircle } from '@phosphor-icons/react/ssr';
import { useQuery } from '@tanstack/react-query';
import { usePathname, useRouter } from 'next/navigation';
import { type ReactNode, useEffect, useState } from 'react';

import { StaffLolaLauncher } from '@/components/assistant/StaffLolaLauncher';
import { ErrorState, NoAccess, Spinner } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { loginHref, signOutEverywhere } from '@/lib/auth';
import { setFormatPreferences } from '@/lib/format';
import { hrefOf, moduleOf } from '@/lib/modules';
import { navGuard } from '@/lib/nav-guard';
import { useStorefrontUrl } from '@/lib/runtime-config';
import { StaffSessionProvider } from '@/lib/session';

import { Header } from './Header';
import { Sidebar, SidebarRail } from './Sidebar';

function Centered({ children }: { children: ReactNode }) {
  return <div className="mx-auto flex w-[min(560px,100%)] flex-col items-center gap-4 px-4 py-16 text-center">{children}</div>;
}

const SIGN_IN_KEY = 'at.st.signInStartedAt';
/** A sign-in that comes back signed out within this window is a loop, not a fresh visit. */
export const SIGN_IN_LOOP_MS = 2 * 60_000;

function readAttempt(): number {
  try {
    return Number(window.sessionStorage.getItem(SIGN_IN_KEY) ?? 0) || 0;
  } catch {
    return 0;
  }
}

function writeAttempt(at: number | null) {
  try {
    if (at === null) window.sessionStorage.removeItem(SIGN_IN_KEY);
    else window.sessionStorage.setItem(SIGN_IN_KEY, String(at));
  } catch {
    /* private mode: the guard is best effort */
  }
}

/**
 * Signed out: the staff console has no sign-in page of its own. Credentials are entered on identity's hosted page
 * (branded for the staff console), so the browser goes straight there and comes back to the page it asked for. A
 * sign-in that comes back still signed out within two minutes stops here instead of sending the browser round again.
 */
function AutoSignIn() {
  const t = useT();
  const [looped, setLooped] = useState(false);
  useEffect(() => {
    const last = readAttempt();
    if (last && Date.now() - last < SIGN_IN_LOOP_MS) {
      setLooped(true);
      return;
    }
    const to = loginHref(window.location.href);
    // A sign-in that keeps coming back (each time after the 2-minute guard was cleared) is a loop: the shell shows it.
    if (!navGuard.allow(to)) return;
    writeAttempt(Date.now());
    window.location.assign(to);
  }, []);
  if (!looped) {
    return (
      <div className="p-8">
        <Spinner label={t('st.consoleShell.signingIn')} />
      </div>
    );
  }
  return (
    <Centered>
      <div role="alert" className="flex flex-col items-center gap-4">
        <h1 className="m-0 text-2xl font-bold">{t('st.consoleShell.signInUnavailable')}</h1>
        <p className="m-0 text-muted">{t('st.consoleShell.signInUnavailableBody')}</p>
        <Button
          onClick={() => {
            navGuard.reset();
            writeAttempt(Date.now());
            window.location.assign(loginHref(window.location.href));
          }}
        >
          {t('st.consoleShell.tryAgain')}
        </Button>
      </div>
    </Centered>
  );
}

function StaffOnly() {
  const storefrontUrl = useStorefrontUrl();
  const t = useT();
  return (
    <Centered>
      <h1 className="m-0 text-2xl font-bold">{t('st.consoleShell.staffOnly')}</h1>
      <p className="m-0 text-muted">{t('st.consoleShell.theStaffConsoleIsFor')}</p>
      <a href={storefrontUrl}>{t('st.consoleShell.goToTheShop')}</a>
    </Centered>
  );
}

/** How long the shell waits quietly before it says so and offers a way out. */
export const SLOW_AFTER_MS = 10_000;
/** Longest wait for one attempt at the session (two retries follow a timeout). */
export const SESSION_TIMEOUT_MS = 12_000;

const LINK_BUTTON = 'inline-flex items-center rounded-pill border border-border px-4 py-1.5 text-sm font-semibold text-text no-underline hover:no-underline';

function SignOutButton() {
  const t = useT();
  return (
    <Button variant="neutral" size="sm" onClick={() => void signOutEverywhere()}>
      <SignOut size={16} aria-hidden /> {t('st.header.signOut')}
    </Button>
  );
}

function ShopLink() {
  const t = useT();
  return (
    <a className={LINK_BUTTON} href={useStorefrontUrl()}>
      {t('st.consoleShell.goToTheShop')}
    </a>
  );
}

function SlowPanel({ children }: { children: ReactNode }) {
  const t = useT();
  return (
    <div role="alert" className="flex flex-col items-center gap-3 rounded-md border border-border bg-surface px-6 py-10 text-center">
      <HourglassMedium size={36} aria-hidden className="text-muted" />
      <strong className="text-lg">{t('st.consoleShell.slowTitle')}</strong>
      <p className="m-0 max-w-[60ch] text-sm text-muted">{t('st.consoleShell.slowBody')}</p>
      <div className="flex flex-wrap justify-center gap-2">{children}</div>
    </div>
  );
}

/**
 * The console's loader: after {@link SLOW_AFTER_MS} from the first loader of the streak (navGuard.loadingSince, so a
 * loader mounted again — a full page load in a loop — keeps counting) it offers Try again, Go to the shop and Sign
 * out. Before the scripts run, CSS alone reveals the same note after 10 s.
 */
export function ConsoleLoading({ label, onRetry, slowAfterMs = SLOW_AFTER_MS }: { label: string; onRetry: () => void; slowAfterMs?: number }) {
  const t = useT();
  const pathname = usePathname();
  const [hydrated, setHydrated] = useState(false);
  const [slow, setSlow] = useState(false);
  useEffect(() => {
    setHydrated(true);
    const since = navGuard.loadingSince();
    const timer = setTimeout(() => setSlow(true), Math.max(0, slowAfterMs - (Date.now() - since)));
    const beat = setInterval(() => navGuard.loadingSince(), 2_000);
    return () => {
      clearTimeout(timer);
      clearInterval(beat);
    };
  }, [slowAfterMs]);
  if (!hydrated) {
    return (
      <div className="flex flex-col gap-4">
        <Spinner label={label} />
        <div className="at-shell-slow-nojs">
          <SlowPanel>
            {/* No scripts yet: plain links (this page again, and the storefront). */}
            <a className={LINK_BUTTON} href={pathname}>
              {t('st.consoleShell.tryAgain')}
            </a>
            <ShopLink />
          </SlowPanel>
        </div>
      </div>
    );
  }
  if (!slow) return <Spinner label={label} />;
  return (
    <SlowPanel>
      <Button
        size="sm"
        onClick={() => {
          navGuard.reset();
          onRetry();
        }}
      >
        {t('st.consoleShell.tryAgain')}
      </Button>
      <ShopLink />
      <SignOutButton />
    </SlowPanel>
  );
}

/** The console stopped a redirect loop: where it kept being sent, with Try again, Go to the shop and Sign out. */
export function ConsoleLooped({ loop }: { loop: NavLoop }) {
  const t = useT();
  return (
    <Centered>
      <div role="alert" className="flex flex-col items-center gap-4">
        <WarningCircle size={40} aria-hidden className="text-warning" />
        <h1 className="m-0 text-2xl font-bold">{t('st.consoleShell.loopTitle')}</h1>
        <p className="m-0 break-words text-muted">
          {t('st.consoleShell.loopBody', { destination: loop.destination, count: String(loop.count), seconds: String(Math.round(loop.windowMs / 1000)) })}
        </p>
        <div className="flex flex-wrap justify-center gap-2">
          <Button
            size="sm"
            onClick={() => {
              navGuard.reset();
              window.location.reload();
            }}
          >
            {t('st.consoleShell.tryAgain')}
          </Button>
          <ShopLink />
          <SignOutButton />
        </div>
      </div>
    </Centered>
  );
}

/** The query's abort signal, also aborted after `ms`. */
function withTimeout(signal: AbortSignal, ms: number): AbortSignal {
  return typeof AbortSignal.any === 'function' && typeof AbortSignal.timeout === 'function' ? AbortSignal.any([signal, AbortSignal.timeout(ms)]) : signal;
}

const allowed = (session: StaffSession, key: ReturnType<typeof moduleOf>) => key !== null && session.navGroups.some((g) => g.modules.some((m) => m.key === key));

/**
 * Console shell: black header, fixed grouped sidebar filtered by RBAC (it never scrolls with the page) and the page.
 * Pages the staff member's roles can't open show an access notice instead of their content.
 */
export function ConsoleShell({ children }: { children: ReactNode }) {
  const t = useT();
  const router = useRouter();
  const pathname = usePathname();
  const api = staffApi();
  const q = useQuery({
    ...staffQueries.session(api),
    // Each attempt gives up after SESSION_TIMEOUT_MS, so a hung request ends in the error panel instead of a loader.
    queryFn: ({ signal }) => api.getSession(withTimeout(signal, SESSION_TIMEOUT_MS)),
    retry: (n, e) => !(isApiError(e) && e.status < 500) && n < 2
  });
  const session = q.data;
  const active = moduleOf(pathname);
  const loop = useNavLoop(navGuard);
  // Signed in: a later 401 (an expired session) may go to sign-in again straight away; the next loader starts afresh.
  useEffect(() => {
    if (session) {
      writeAttempt(null);
      navGuard.loaded();
    }
  }, [session]);

  if (loop) {
    return (
      <>
        <Header session={session ?? null} />
        <ConsoleLooped loop={loop} />
      </>
    );
  }
  if (q.isPending) {
    return (
      <>
        <Header session={null} />
        <div className="p-8">
          <ConsoleLoading label={t('st.consoleShell.loadingTheStaffConsole')} onRetry={() => void q.refetch()} />
        </div>
      </>
    );
  }
  if (q.isError || !session) {
    const e = q.error;
    return (
      <>
        <Header session={null} />
        {isApiError(e) && e.status === 401 ? (
          <AutoSignIn />
        ) : isApiError(e) && e.code === 'NOT_STAFF' ? (
          <StaffOnly />
        ) : (
          <div className="p-8">
            <ErrorState error={e} onRetry={() => void q.refetch()} />
          </div>
        )}
      </>
    );
  }
  // My account (/account/…) is every staff member's own, whatever their roles open.
  const canOpen = accountSectionOf(pathname) !== null || allowed(session, active);
  return (
    <StaffSessionProvider session={session}>
      <BackofficeFrame
        header={(menuButton) => <Header session={session} menuButton={menuButton} />}
        nav={<Sidebar session={session} active={active} />}
        rail={<SidebarRail session={session} active={active} />}
        routeKey={pathname}
        menuLabel={t('st.consoleShell.menu')}
        expandLabel={t('st.consoleShell.expandMenu')}
        collapseLabel={t('st.consoleShell.collapseMenu')}
      >
        <main id="main" tabIndex={-1} className="flex min-w-0 flex-col gap-5 px-4 pt-6 pb-12 outline-none sm:px-8">
          {canOpen ? (
            <AccountFormatPreferences apply={setFormatPreferences}>{children}</AccountFormatPreferences>
          ) : (
            <>
              <NoAccess roles={session.roleLabel} />
              <div className="flex justify-center">
                <Button variant="neutral" onClick={() => router.push(hrefOf(session.landing))}>
                  Go to {session.navGroups[0]?.modules[0]?.label ?? 'the dashboard'}
                </Button>
              </div>
            </>
          )}
        </main>
      </BackofficeFrame>
      <StaffLolaLauncher />
    </StaffSessionProvider>
  );
}
