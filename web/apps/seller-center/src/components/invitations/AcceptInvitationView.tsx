'use client';

import { isApiError, sellerKeys, sellerQueries } from '@afrotransact/api-client';
import { Button, Logo } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ReactNode } from 'react';

import { teamError } from '@/components/team/TeamView';
import { sellerApi } from '@/lib/api';
import { storefrontUrl } from '@/lib/config';
import { shortDate } from '@/lib/format';

const linkClass =
  'inline-flex items-center justify-center rounded-pill px-6 py-2.5 font-bold no-underline hover:no-underline';

function Card({ title, children }: { title: string; children: ReactNode }) {
  return (
    <main id="main" className="mx-auto flex w-[min(520px,100%)] flex-col gap-4 px-4 py-12">
      <Logo />
      <section className="flex flex-col gap-3 rounded-lg border border-border bg-surface p-6">
        <h1 className="m-0 text-2xl font-bold">{title}</h1>
        {children}
      </section>
    </main>
  );
}

/** Invitation accept page (S236): shows what the link is for (public lookup), then joins the signed-in person, or
    sends people without an account to sign up with the invitation (identity skips the email code and joins them). */
export function AcceptInvitationView({ token }: { token: string }) {
  const api = sellerApi();
  const qc = useQueryClient();
  const lookup = useQuery({ queryKey: ['invitation', token], queryFn: () => api.lookupInvitation(token), enabled: token !== '', retry: false });
  const session = useQuery({ ...sellerQueries.session(api), retry: false });
  const signedIn = session.isSuccess || (session.isError && !(isApiError(session.error) && session.error.status === 401));
  const accept = useMutation({
    mutationFn: () => api.acceptInvitation(token),
    onSuccess: () => void qc.invalidateQueries({ queryKey: sellerKeys.all })
  });

  if (!token) {
    return (
      <Card title="This link is incomplete">
        <p className="m-0 text-muted">Open the invitation again from the email, or ask the organisation to send a new one.</p>
      </Card>
    );
  }
  if (lookup.isPending || session.isPending) {
    return (
      <Card title="Checking your invitation…">
        <p role="status" className="m-0 text-muted">One moment.</p>
      </Card>
    );
  }
  if (lookup.isError) {
    return (
      <Card title="We couldn’t open this invitation">
        <p role="alert" className="m-0 text-muted">{teamError(lookup.error)}</p>
      </Card>
    );
  }
  const inv = lookup.data;
  if (accept.isSuccess) {
    const joined = accept.data;
    return (
      <Card title={`Welcome to ${joined.orgName}`}>
        <p className="m-0">
          {joined.alreadyMember ? 'You were already on this team; your role stays ' : 'You joined as '}
          <strong>{joined.roleLabel}</strong>.
        </p>
        <a className={`${linkClass} self-start bg-primary text-on-primary hover:bg-primary-hover hover:text-on-primary`} href="/">
          Open {joined.orgKind === 'PROVIDER' ? 'Provider Hub' : 'Seller Center'}
        </a>
      </Card>
    );
  }
  if (inv.status !== 'PENDING') {
    const why = { EXPIRED: 'This invitation has expired.', REVOKED: 'This invitation was withdrawn.', ACCEPTED: 'This invitation has already been used.' }[inv.status];
    return (
      <Card title={`Invitation to ${inv.orgName}`}>
        <p role="alert" className="m-0 text-muted">{why} Ask the organisation to send a new one.</p>
      </Card>
    );
  }
  const here = typeof window !== 'undefined' ? window.location.href : '/';
  return (
    <Card title={`Join ${inv.orgName}`}>
      <p className="m-0">
        You’re invited as <strong>{inv.roleLabel}</strong>. The invitation was sent to <strong>{inv.email}</strong> and expires {shortDate(inv.expiresAt)}.
      </p>
      {signedIn ? (
        <>
          {accept.isError ? <p role="alert" className="m-0 text-sm text-danger">{teamError(accept.error)}</p> : null}
          <Button className="self-start" loading={accept.isPending} onClick={() => accept.mutate()}>
            Accept invitation
          </Button>
        </>
      ) : (
        <>
          <p className="m-0 text-sm text-muted">Sign in with {inv.email}, or create an account with that address if you don’t have one yet.</p>
          <div className="flex flex-wrap gap-2">
            <a className={`${linkClass} bg-primary text-on-primary hover:bg-primary-hover hover:text-on-primary`} href={`${storefrontUrl()}/signin?returnTo=${encodeURIComponent(here)}`}>
              Sign in
            </a>
            <a
              className={`${linkClass} border border-border-strong text-ink-1000`}
              href={`${storefrontUrl()}/signup?invitation=${encodeURIComponent(token)}&email=${encodeURIComponent(inv.email)}`}
            >
              Create an account
            </a>
          </div>
        </>
      )}
    </Card>
  );
}
