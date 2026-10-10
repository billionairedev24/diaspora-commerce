'use client';

import { NewsletterRequestSchema, type SiteConfig } from '@afrotransact/api-client';
import { AppleGlyph, Globe, GooglePlayLogo, Logo, Phone, SocialIconLink, useToast } from '@afrotransact/ui';
import { useMutation, useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { type FormEvent, type ReactNode, useState } from 'react';

import { type MessageKey, t } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { searchHref, siteQueries } from '@/lib/api/queries';

import styles from './Footer.module.css';

type FooterLink = { label: MessageKey | string; href: string; external?: boolean };

export const FOOTER_COLUMNS: Array<{ title: MessageKey; links: FooterLink[] }> = [
  {
    title: 'footer.getToKnow',
    links: [
      { label: 'footer.about', href: '/about' },
      { label: 'footer.careers', href: '/careers' },
      { label: 'footer.press', href: '/help/press' },
      { label: 'footer.brand', href: searchHref({ sellerKind: 'PLATFORM' }) }
    ]
  },
  {
    title: 'footer.makeMoney',
    links: [
      { label: 'footer.sell', href: '/sell', external: true },
      { label: 'footer.provide', href: '/provide', external: true },
      { label: 'footer.fba', href: '/help/fulfilled-by-afrotransact' },
      { label: 'footer.advertise', href: '/help/advertise' },
      { label: 'footer.drive', href: '/careers?team=Delivery' }
    ]
  },
  {
    title: 'footer.help',
    links: [
      { label: 'footer.account', href: '/account' },
      { label: 'footer.orders', href: '/orders' },
      { label: 'footer.shipping', href: '/help/shipping' },
      { label: 'footer.returns', href: '/account/returns' },
      { label: 'footer.helpCenter', href: '/help' },
      { label: 'footer.accessibility', href: '/legal/accessibility' }
    ]
  },
  {
    title: 'footer.byCommunity',
    links: [
      ...['Nigerian', 'Ghanaian', 'Ethiopian', 'Indian', 'Jamaican'].map((c) => ({ label: c, href: searchHref({ community: c }) })),
      { label: 'footer.allCommunities', href: '/s' }
    ]
  }
];

const label = (l: string) => (l.includes('.') ? t(l as MessageKey) : l);

function Newsletter() {
  const [email, setEmail] = useState('');
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const m = useMutation({ mutationFn: (e: string) => browserApi().subscribeNewsletter(e) });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const parsed = NewsletterRequestSchema.safeParse({ email: email.trim() });
    if (!parsed.success) {
      setMsg({ ok: false, text: t('footer.newsletterInvalid') });
      return;
    }
    m.mutate(parsed.data.email, {
      onSuccess: () => {
        setMsg({ ok: true, text: t('footer.newsletterOk', { email: parsed.data.email }) });
        setEmail('');
      },
      onError: () => setMsg({ ok: false, text: t('footer.newsletterError') })
    });
  };
  return (
    <form onSubmit={submit} noValidate>
      <label htmlFor="footer-news" className={styles.newsTitle}>
        {t('footer.newsletterTitle')}
      </label>
      <div className={styles.newsRow} style={{ marginTop: 8 }}>
        <input
          id="footer-news"
          className={styles.newsInput}
          type="email"
          value={email}
          onChange={(e) => {
            setEmail(e.target.value);
            setMsg(null);
          }}
          placeholder="you@example.com"
          aria-describedby="footer-news-msg"
          aria-invalid={msg && !msg.ok ? true : undefined}
          autoComplete="email"
        />
        <button type="submit" className={styles.newsBtn} disabled={m.isPending}>
          {t('footer.subscribe')}
        </button>
      </div>
      <p id="footer-news-msg" className={`${styles.newsMsg} ${msg?.ok ? styles.newsOk : styles.newsErr}`} aria-live="polite">
        {msg?.text}
      </p>
    </form>
  );
}

export interface FooterViewProps {
  config: SiteConfig | undefined;
  locale?: string;
  year?: number;
}

/** Footer per design; social links, phone, legal links, locales and payment chips come from site config. */
export function FooterView({ config, locale = 'en', year = new Date().getFullYear() }: FooterViewProps) {
  const router = useRouter();
  const toast = useToast();
  const phone = config?.contacts.phone ?? '';
  const appLink = (href: string | null | undefined, small: string, big: string, icon: ReactNode) =>
    href ? (
      <a className={styles.appBtn} href={href} target="_blank" rel="noopener noreferrer">
        {icon}
        <span className={styles.appText}>
          <span className={styles.appSmall}>{small}</span>
          <strong>{big}</strong>
        </span>
      </a>
    ) : (
      <button type="button" className={styles.appBtn} onClick={() => toast.show(t('footer.appSoon'))}>
        {icon}
        <span className={styles.appText}>
          <span className={styles.appSmall}>{small}</span>
          <strong>{big}</strong>
        </span>
      </button>
    );

  return (
    <footer className={styles.footer}>
      <button type="button" className={styles.top} onClick={() => window.scrollTo({ top: 0, behavior: 'smooth' })}>
        {t('footer.backToTop')}
      </button>
      <div className={styles.grid}>
        <div className={styles.brand}>
          <Logo size={36} />
          <p className={styles.blurb}>{t('footer.blurb')}</p>
          <Newsletter />
          {phone && (
            <a className={styles.phone} href={`tel:${phone.replace(/[^\d+]/g, '')}`}>
              <Phone size={20} aria-hidden />
              <span>
                <span className={styles.phoneLabel}>{t('footer.helpLabel')}</span>
                <strong>{phone}</strong>
              </span>
            </a>
          )}
          {config && config.socials.length > 0 && (
            <ul className={styles.socials} aria-label={t('footer.followUs')}>
              {config.socials.map((s) => (
                <li key={s.network}>
                  <SocialIconLink network={s.network} href={s.url} label={s.label} newTabLabel={t('footer.newTab')} />
                </li>
              ))}
            </ul>
          )}
        </div>
        {FOOTER_COLUMNS.map((col) => (
          <nav key={col.title} className={styles.col} aria-label={t(col.title)}>
            <h2 className={styles.colTitle}>{t(col.title)}</h2>
            <ul className={styles.colList}>
              {col.links.map((l) => (
                <li key={l.href + l.label}>
                  {l.external ? (
                    <a className={styles.link} href={l.href}>
                      {label(l.label)}
                    </a>
                  ) : (
                    <Link className={styles.link} href={l.href}>
                      {label(l.label)}
                    </Link>
                  )}
                </li>
              ))}
            </ul>
          </nav>
        ))}
      </div>
      <div className={styles.apps}>
        <span className={styles.appsTitle}>{t('footer.getApp')}</span>
        {appLink(config?.appLinks.ios, t('footer.appStoreSmall'), t('footer.appStore'), <AppleGlyph size={24} weight="fill" aria-hidden />)}
        {appLink(config?.appLinks.android, t('footer.playSmall'), t('footer.play'), <GooglePlayLogo size={24} weight="fill" aria-hidden />)}
        <span className={styles.flex} />
        <ul className={styles.chips} aria-label={t('footer.paymentMethods')}>
          {(config?.paymentMethods ?? []).map((p) => (
            <li key={p} className={styles.chip}>
              {p}
            </li>
          ))}
        </ul>
      </div>
      <div className={styles.bottom}>
        <div className={styles.bottomInner}>
          <label className={styles.lang}>
            <Globe aria-hidden />
            <span className="at-visually-hidden">{t('footer.language')}</span>
            <select
              className={styles.langSelect}
              value={locale}
              onChange={(e) => {
                document.cookie = `at_locale=${e.target.value}; path=/; max-age=31536000; samesite=lax`;
                router.refresh();
              }}
            >
              {(config?.locales ?? [{ code: 'en', name: 'English', dir: 'ltr' as const }]).map((l) => (
                <option key={l.code} value={l.code} lang={l.code}>
                  {l.name}
                </option>
              ))}
            </select>
            <span>{t('footer.market', { currency: config?.market.currency ?? 'USD', country: config?.market.country ?? 'United States' })}</span>
          </label>
          <nav aria-label={t('footer.legal')}>
            <ul className={styles.legal}>
              {(config?.legal ?? []).map((l) => (
                <li key={l.key}>
                  <Link className={styles.legalLink} href={l.href}>
                    {l.title}
                  </Link>
                </li>
              ))}
            </ul>
          </nav>
          <span className={styles.flex} />
          <span>{t('footer.copyright', { year })}</span>
        </div>
      </div>
    </footer>
  );
}

export function Footer({ locale }: { locale?: string }) {
  const { data } = useQuery(siteQueries.config(browserApi()));
  return <FooterView config={data} locale={locale} />;
}
