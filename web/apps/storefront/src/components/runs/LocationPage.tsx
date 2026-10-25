'use client';

import { accountQueries, deliveryRunQueries, isApiError, type PublicRun, type RunLocationPage } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { TextField } from '@afrotransact/ui';
import { useMutation, useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { type FormEvent, useMemo, useState } from 'react';

import accountStyles from '@/components/account/Pages.module.css';
import { Countdown } from '@/components/preorder/Countdown';
import preorderStyles from '@/components/preorder/Preorder.module.css';
import { type MessageKey, useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shareEntries, subscriptionSource } from '@/lib/runs/source';

import { ChannelToggles } from './ChannelToggles';
import { RunAdvertorials } from './RunAdvertorials';
import styles from './Runs.module.css';
import { useRunSubscriptions } from './useRunSubscriptions';

function RunCard({ run, currency }: { run: PublicRun; currency: string }) {
  const t = useT();
  const fmt = useFormatters();
  const open = run.acceptingOrders && !run.full;
  return (
    <li className={styles.run} data-testid="location-run">
      <span className={`${styles.tag} ${open ? '' : styles.tagFull}`}>{open ? t('runs.run.open') : run.full ? t('runs.run.full') : t('runs.run.closed')}</span>
      <span className={styles.runWhen}>{fmt.date(run.windowStart, 'weekday')}</span>
      <dl className={styles.runFacts}>
        <dt>{t('runs.run.window')}</dt>
        <dd>{`${fmt.time(run.windowStart)} - ${fmt.time(run.windowEnd)}`}</dd>
        <dt>{t('runs.run.fee')}</dt>
        <dd>{fmt.money(run.feeMinor, run.currency || currency)}</dd>
        <dt>{t('runs.run.how')}</dt>
        <dd>{run.dropMode === 'PICKUP_POINT' ? t('runs.run.pickupPoint') : t('runs.run.doorstep')}</dd>
        {run.ordersLeft !== null ? (
          <>
            <dt>{t('runs.run.left')}</dt>
            <dd>{fmt.number(run.ordersLeft)}</dd>
          </>
        ) : null}
      </dl>
      {open ? <Countdown variant="inline" to={run.cutoffAt} label={t('runs.run.closesIn')} /> : null}
    </li>
  );
}

/** Guests: email sign-up with a confirmation link (double opt-in). Signed in: the channel choices right here. */
function Subscribe({ page }: { page: RunLocationPage }) {
  const t = useT();
  const api = browserApi();
  const params = useSearchParams();
  const source = subscriptionSource('location-page', params);
  const me = useQuery(accountQueries.me(api));
  const signedIn = Boolean(me.data?.user);
  const { subscriptions, pending, error, toggle } = useRunSubscriptions(source);
  const [email, setEmail] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const join = useMutation({
    mutationFn: (e: string) => api.subscribeRunAlerts({ locationId: page.location.id, email: e, source }),
    onError: (e) => setProblem(isApiError(e) ? (e.fieldErrors.email ?? Object.values(e.fieldErrors)[0] ?? e.message) : t('runs.error.generic'))
  });
  if (me.isPending) return null;
  if (signedIn)
    return (
      <section className={accountStyles.card} aria-labelledby="runs-subscribe" data-testid="location-subscribe">
        <h2 id="runs-subscribe" className={accountStyles.h2}>
          {t('runs.subscribe.title', { place: page.location.name })}
        </h2>
        <p className={accountStyles.muted}>{t('runs.subscribe.signedIn')}</p>
        <ChannelToggles
          locationName={page.location.name}
          subscription={subscriptions.find((s) => s.locationId === page.location.id)}
          pending={pending?.locationId === page.location.id ? pending.channel : null}
          onToggle={(channel, on) => toggle(page.location.id, channel, on)}
        />
        {error ? (
          <p role="alert" className={accountStyles.error}>
            {error}
          </p>
        ) : null}
        <Link href="/account/delivery-alerts">{t('runs.subscribe.manage')}</Link>
      </section>
    );
  if (join.isSuccess)
    return (
      <section className={accountStyles.card} data-testid="location-subscribe" data-state="sent" aria-live="polite">
        <h2 className={accountStyles.h2}>{t('runs.subscribe.sentTitle')}</h2>
        <p className={accountStyles.muted}>{t('runs.subscribe.sentBody', { email })}</p>
      </section>
    );
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const value = email.trim();
    if (!/^\S+@\S+\.\S+$/.test(value)) return setProblem(t('runs.subscribe.emailInvalid'));
    setProblem(null);
    join.mutate(value);
  };
  return (
    <section className={accountStyles.card} aria-labelledby="runs-subscribe" data-testid="location-subscribe" data-state="ask">
      <h2 id="runs-subscribe" className={accountStyles.h2}>
        {t('runs.subscribe.title', { place: page.location.name })}
      </h2>
      <p className={accountStyles.muted}>{t('runs.subscribe.body')}</p>
      <form noValidate className={styles.subscribeForm} onSubmit={submit}>
        <TextField label={t('runs.subscribe.email')} type="email" autoComplete="email" value={email} error={problem} onChange={(e) => setEmail(e.currentTarget.value)} />
        <button type="submit" className={preorderStyles.inkButton} disabled={join.isPending}>
          {join.isPending ? t('runs.subscribe.pending') : t('runs.subscribe.cta')}
        </button>
      </form>
      <p className={styles.hint}>{t('runs.subscribe.consent')}</p>
    </section>
  );
}

const SHARE_KEY: Record<string, MessageKey> = { whatsapp: 'runs.share.whatsapp', facebook: 'runs.share.facebook' };

function Share({ links }: { links: Record<string, string> }) {
  const t = useT();
  const entries = shareEntries(links);
  const [copied, setCopied] = useState(false);
  if (entries.length === 0) return null;
  return (
    <div className={styles.share} data-testid="location-share">
      <strong>{t('runs.share.title')}</strong>
      {entries.map(({ channel, url }) =>
        channel === 'copy' ? (
          <button
            key={channel}
            type="button"
            className={preorderStyles.outlineButton}
            onClick={() => {
              void navigator.clipboard?.writeText(url).then(() => setCopied(true));
            }}
          >
            {copied ? t('runs.share.copied') : t('runs.share.copy')}
          </button>
        ) : (
          <a key={channel} className={preorderStyles.outlineButton} href={url} rel="noopener noreferrer" target="_blank">
            {SHARE_KEY[channel] ? t(SHARE_KEY[channel]) : t('runs.share.other', { channel })}
          </a>
        )
      )}
    </div>
  );
}

/** /delivery-runs/<slug> (S262, S263): a location's upcoming runs, advertorials, WhatsApp community, sharing and alerts. */
export function LocationPageView({ slug }: { slug: string }) {
  const t = useT();
  const api = browserApi();
  const q = useQuery(deliveryRunQueries.location(api, slug));
  const zip = (q.data?.location.area?.zips as string[] | undefined)?.[0];
  const place = useMemo(() => (zip ? { zip } : undefined), [zip]);
  if (q.isPending) return <p className={accountStyles.muted}>{t('runs.loading')}</p>;
  if (q.isError)
    return (
      <section className={preorderStyles.unsubscribeCard} data-testid="location-missing">
        <h1 className={preorderStyles.offTitle}>{isApiError(q.error) && q.error.status === 404 ? t('runs.location.missingTitle') : t('runs.error.load')}</h1>
        <p className={preorderStyles.offBody}>{t('runs.location.missingBody')}</p>
        <div className={preorderStyles.unsubscribeActions}>
          <Link href="/delivery-runs" className={preorderStyles.inkButton}>
            {t('runs.location.all')}
          </Link>
        </div>
      </section>
    );
  const page = q.data;
  return (
    <div className={accountStyles.accountPage} data-testid="location-page">
      <header className={styles.locationHead}>
        <nav className={accountStyles.crumb} aria-label={t('acct.breadcrumb')}>
          <Link href="/delivery-runs">{t('runs.location.all')}</Link>
          <span aria-hidden>›</span>
          <span aria-current="page" className={accountStyles.crumbCurrent}>
            {page.location.name}
          </span>
        </nav>
        <h1 className={accountStyles.h1}>{t('runs.location.title', { place: page.location.name })}</h1>
        <p className={accountStyles.muted}>{t('runs.location.intro', { place: page.location.name })}</p>
      </header>
      <RunAdvertorials place={place} />
      <section aria-labelledby="runs-upcoming">
        <h2 id="runs-upcoming" className={accountStyles.h2}>
          {t('runs.location.upcoming')}
        </h2>
        {page.runs.length === 0 ? (
          <p className={accountStyles.muted} data-testid="location-no-runs">
            {t('runs.location.none')}
          </p>
        ) : (
          <ul className={styles.runs}>
            {page.runs.map((r) => (
              <RunCard key={r.runId} run={r} currency={page.location.currency} />
            ))}
          </ul>
        )}
      </section>
      <Subscribe page={page} />
      {page.location.whatsappInviteUrl ? (
        <a className={preorderStyles.outlineButton} href={page.location.whatsappInviteUrl} rel="noopener noreferrer" target="_blank" data-testid="location-community">
          {t('runs.community', { place: page.location.name })}
        </a>
      ) : null}
      <Share links={page.shareLinks} />
    </div>
  );
}

/** /delivery-runs: every location with delivery runs. */
export function LocationsIndex() {
  const t = useT();
  const api = browserApi();
  const q = useQuery(deliveryRunQueries.locations(api));
  return (
    <div className={accountStyles.accountPage} data-testid="locations-index">
      <h1 className={accountStyles.h1}>{t('runs.index.title')}</h1>
      <p className={accountStyles.muted}>{t('runs.index.intro')}</p>
      <RunAdvertorials />
      {q.isPending ? <p className={accountStyles.muted}>{t('runs.loading')}</p> : null}
      {q.isError ? (
        <p role="alert" className={accountStyles.error}>
          {t('runs.error.load')}
        </p>
      ) : null}
      {q.data && q.data.items.length === 0 ? <p className={accountStyles.muted}>{t('runs.index.none')}</p> : null}
      <ul className={styles.locationList}>
        {q.data?.items.map((l) => (
          <li key={l.id}>
            <Link className={styles.locationLink} href={`/delivery-runs/${l.slug}`}>
              <strong>{l.name}</strong>
              <span className={accountStyles.muted}>{l.state ? `${l.state}, ${l.market}` : l.market}</span>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}

