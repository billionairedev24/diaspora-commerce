'use client';

import { AccountFormatPreferences, accountSectionOf } from '@afrotransact/account';
import { isApiError, staffQueries,type StaffSession } from '@afrotransact/api-client';
import { BackofficeFrame, Button } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { usePathname, useRouter } from 'next/navigation';
import { type ReactNode, useEffect, useState } from 'react';

import { ErrorState, NoAccess, Spinner } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { loginHref } from '@/lib/auth';
import { setFormatPreferences } from '@/lib/format';
import { hrefOf, moduleOf } from '@/lib/modules';
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
    writeAttempt(Date.now());
    window.location.assign(loginHref(window.location.href));
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

const allowed = (session: StaffSession, key: ReturnType<typeof moduleOf>) => key !== null && session.navGroups.some((g) => g.modules.some((m) => m.key === key));

/**
 * Console shell: black header, fixed grouped sidebar filtered by RBAC (it never scrolls with the page) and the page.
 * Pages the staff member's roles can't open show an access notice instead of their content.
 */
export function ConsoleShell({ children }: { children: ReactNode }) {
  const t = useT();
  const router = useRouter();
  const pathname = usePathname();
  const q = useQuery({ ...staffQueries.session(staffApi()), retry: (n, e) => !(isApiError(e) && e.status < 500) && n < 2 });
  const session = q.data;
  const active = moduleOf(pathname);
  // Signed in: a later 401 (an expired session) may go to sign-in again straight away.
  useEffect(() => {
    if (session) writeAttempt(null);
  }, [session]);

  if (q.isPending) {
    return (
      <>
        <Header session={null} />
        <div className="p-8">
          <Spinner label={t('st.consoleShell.loadingTheStaffConsole')} />
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
    </StaffSessionProvider>
  );
}
