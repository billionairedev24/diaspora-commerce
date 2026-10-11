'use client';

import { isApiError, staffQueries,type StaffSession } from '@afrotransact/api-client';
import { Button } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { usePathname, useRouter } from 'next/navigation';
import { type ReactNode } from 'react';

import { ErrorState, NoAccess, Spinner } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { loginHref } from '@/lib/auth';
import { hrefOf, moduleOf } from '@/lib/modules';
import { useStorefrontUrl } from '@/lib/runtime-config';
import { StaffSessionProvider } from '@/lib/session';

import { Header } from './Header';
import { Sidebar } from './Sidebar';

function Centered({ children }: { children: ReactNode }) {
  return <div className="mx-auto flex w-[min(560px,100%)] flex-col items-center gap-4 px-4 py-16 text-center">{children}</div>;
}

function SignInPrompt() {
  const t = useT();
  const returnTo = typeof window !== 'undefined' ? window.location.href : '/';
  return (
    <Centered>
      <h1 className="m-0 text-2xl font-bold">{t('st.consoleShell.signInToTheStaff')}</h1>
      <p className="m-0 text-muted">{t('st.consoleShell.useYourAfrotransactComStaff')}</p>
      <a className="rounded-pill bg-primary px-6 py-2.5 font-bold text-on-primary no-underline hover:bg-primary-hover hover:text-on-primary hover:no-underline" href={loginHref(returnTo)}>
        {t('st.consoleShell.signIn')}
      </a>
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
          <SignInPrompt />
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
  const sidebar = <Sidebar session={session} active={active} />;
  const canOpen = allowed(session, active);
  return (
    <StaffSessionProvider session={session}>
      <Header session={session} />
      <div className="grid min-h-[calc(100vh-56px)] grid-cols-1 md:grid-cols-[264px_minmax(0,1fr)]">
        <aside className="border-b border-border bg-surface md:sticky md:top-14 md:h-[calc(100vh-56px)] md:self-start md:overflow-y-auto md:overscroll-contain md:border-r md:border-b-0">{sidebar}</aside>
        <main id="main" tabIndex={-1} className="flex min-w-0 flex-col gap-5 px-4 pt-6 pb-12 outline-none sm:px-8">
          {canOpen ? (
            children
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
      </div>
    </StaffSessionProvider>
  );
}
