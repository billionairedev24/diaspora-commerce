'use client';

import { isApiError, staffAccessKeys } from '@afrotransact/api-client';
import { Button, Logo, PasswordField, Spinner } from '@afrotransact/ui';
import { useMutation, useQuery } from '@tanstack/react-query';
import { type FormEvent, useEffect, useState } from 'react';

import { problemMessage } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { loginHref } from '@/lib/auth';
import { dateTime } from '@/lib/format';

const MIN_PASSWORD = 12;

/** The token after `#token=`; `null` until it has been read. The fragment is removed from the address bar afterwards. */
function useInviteToken(): string | null {
  const [token, setToken] = useState<string | null>(null);
  useEffect(() => {
    const m = /(?:^|[#&])token=([^&]+)/.exec(window.location.hash);
    let value = '';
    try {
      value = m?.[1] ? decodeURIComponent(m[1]) : '';
    } catch {
      value = '';
    }
    setToken(value);
    if (value) window.history.replaceState(null, '', window.location.pathname);
  }, []);
  return token;
}

/**
 * Where an invited person lands from the email: who they are invited as, then a password of their own. A minimal page with no staff
 * navigation. The link works once and expires; a used, withdrawn or expired link says so and what to do next. After the password is
 * set the person signs in as usual and sets up two-step verification the first time.
 */
export function InviteAccept() {
  const t = useT();
  const api = staffApi();
  const token = useInviteToken();
  const preview = useQuery({
    queryKey: [...staffAccessKeys.all, 'invite-preview', token ? 'present' : 'absent'],
    queryFn: ({ signal }) => api.access.previewInvitation(token ?? '', signal),
    enabled: Boolean(token),
    retry: false,
    staleTime: Infinity,
    gcTime: 0
  });
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const accept = useMutation({
    mutationFn: () => api.access.acceptInvitation({ token: token ?? '', password, confirmPassword: confirm }),
    retry: false,
    onSuccess: () => {
      setPassword('');
      setConfirm('');
    },
    onError: (e) => {
      setErrors(isApiError(e) ? e.fieldErrors : {});
      setFailure(isApiError(e) && Object.keys(e.fieldErrors).length ? null : problemMessage(e));
    }
  });
  const data = preview.data;
  const signIn = () => loginHref(`${window.location.origin}/`, data ? { loginHint: data.email } : {});

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setErrors({});
    setFailure(null);
    if (password.length < MIN_PASSWORD) return setErrors({ password: t('st.inv.accept.tooShort', { min: MIN_PASSWORD }) });
    if (password !== confirm) return setErrors({ confirmPassword: t('st.inv.accept.mismatch') });
    accept.mutate();
  };

  let body;
  if (token === null || (token && !data && !preview.isError)) {
    body = (
      <Spinner size="sm" label={t('st.inv.accept.loading')} />
    );
  } else if (!token || (preview.isError && isApiError(preview.error) && preview.error.status === 404)) {
    body = <Message title={t('st.inv.accept.invalid.title')}>{t('st.inv.accept.invalid.body')}</Message>;
  } else if (preview.isError) {
    body = <Message title={t('st.inv.accept.error.title')}>{problemMessage(preview.error)}</Message>;
  } else if (data && accept.isSuccess) {
    body = (
      <Message title={t('st.inv.accept.done.title')}>
        <p className="m-0">{t('st.inv.accept.done.body', { email: data.email })}</p>
        <p className="mt-4 mb-0">
          <a className="font-semibold" href={signIn()} data-testid="invite-signin">
            {t('st.inv.accept.signIn')}
          </a>
        </p>
      </Message>
    );
  } else if (!data) {
    body = <Message title={t('st.inv.accept.invalid.title')}>{t('st.inv.accept.invalid.body')}</Message>;
  } else if (data.status === 'EXPIRED') {
    body = <Message title={t('st.inv.accept.expired.title')}>{t('st.inv.accept.expired.body', { name: data.firstName })}</Message>;
  } else if (data.status === 'REVOKED') {
    body = <Message title={t('st.inv.accept.revoked.title')}>{t('st.inv.accept.revoked.body')}</Message>;
  } else if (data.status === 'ACCEPTED') {
    body = (
      <Message title={t('st.inv.accept.used.title')}>
        <p className="m-0">{t('st.inv.accept.used.body')}</p>
        <p className="mt-4 mb-0">
          <a className="font-semibold" href={signIn()}>
            {t('st.inv.accept.signIn')}
          </a>
        </p>
      </Message>
    );
  } else {
    const p = data;
    body = (
      <form className="flex flex-col gap-4" onSubmit={submit} noValidate>
        <h1 className="m-0 text-2xl font-bold">{t('st.inv.accept.title', { name: p.firstName })}</h1>
        <p className="m-0 text-muted">{t('st.inv.accept.intro', { role: p.roleName })}</p>
        <dl className="m-0 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
          <dt className="text-muted">{t('st.inv.accept.email')}</dt>
          <dd className="m-0 font-semibold" data-testid="invite-email">
            {p.email}
          </dd>
          <dt className="text-muted">{t('st.inv.accept.role')}</dt>
          <dd className="m-0 font-semibold">{p.roleName}</dd>
        </dl>
        <PasswordField
          label={t('st.inv.accept.password')}
          required
          autoComplete="new-password"
          value={password}
          onChange={(e) => setPassword(e.currentTarget.value)}
          error={errors.password}
          help={t('st.inv.accept.passwordHelp', { min: MIN_PASSWORD })}
          showLabel={t('st.inv.accept.show')}
          hideLabel={t('st.inv.accept.hide')}
        />
        <PasswordField
          label={t('st.inv.accept.confirm')}
          required
          autoComplete="new-password"
          value={confirm}
          onChange={(e) => setConfirm(e.currentTarget.value)}
          error={errors.confirmPassword}
          showLabel={t('st.inv.accept.show')}
          hideLabel={t('st.inv.accept.hide')}
        />
        {failure ? (
          <p role="alert" className="m-0 text-sm text-danger">
            {failure}
          </p>
        ) : null}
        <Button type="submit" loading={accept.isPending}>
          {t('st.inv.accept.submit')}
        </Button>
        <p className="m-0 text-xs text-muted">{t('st.inv.accept.expires', { when: dateTime(p.expiresAt) })}</p>
        <p className="m-0 text-xs text-muted">{t('st.inv.accept.mfa')}</p>
      </form>
    );
  }
  return (
    <main id="main" className="mx-auto flex min-h-dvh w-full max-w-[28rem] flex-col gap-8 px-4 py-10">
      <Logo label="AfroTransact" />
      {body}
    </main>
  );
}

function Message({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-2" data-testid="invite-message">
      <h1 className="m-0 text-2xl font-bold">{title}</h1>
      <div className="text-muted">{children}</div>
    </div>
  );
}
