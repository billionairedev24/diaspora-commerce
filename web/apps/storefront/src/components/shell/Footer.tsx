'use client';

import { type LegalDocumentKey, legalDocumentKeyOf, legalHref, NewsletterRequestSchema, type SiteConfig } from '@afrotransact/api-client';
import { isLocale, localeCookie, LOCALES } from '@afrotransact/i18n';
import { useLocale } from '@afrotransact/i18n/react';
import { AppleGlyph, CaretDown, Globe, GooglePlayLogo, Logo, Phone, SocialIconLink, useToast } from '@afrotransact/ui';
import { useMutation, useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { type FormEvent, type ReactNode, useId, useState } from 'react';

import { type MessageKey, type Translator, useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { searchHref, siteQueries } from '@/lib/api/queries';
import { useMediaQuery } from '@/lib/ui/useMediaQuery';

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
      { label: 'footer.orderHelp', href: '/account/help' },
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

const label = (t: Translator<MessageKey>, l: string) => (l.includes('.') ? t(l as MessageKey) : l);

/** Bottom-bar legal links in the design's order (Shop design footer); shown when site config publishes the document. */
export const FOOTER_LEGAL: ReadonlyArray<[LegalDocumentKey, MessageKey]> = [
  ['conditions-of-use', 'legal.doc.conditions-of-use'],
  ['privacy', 'legal.doc.privacy'],
  ['cookies', 'legal.cookiePrefs'],
  ['privacy-choices', 'legal.doc.privacy-choices'],
  ['sms-terms', 'legal.doc.sms-terms'],
  ['returns', 'legal.doc.returns'],
  ['accessibility', 'legal.doc.accessibility']
];

function footerLegal(config: SiteConfig | undefined): Array<[LegalDocumentKey, MessageKey]> {
  const published = new Set((config?.legal ?? []).map((l) => legalDocumentKeyOf(l.key)).filter((k) => k !== null));
  return FOOTER_LEGAL.filter(([k]) => published.has(k));
}

function Newsletter() {
  const t = useT();
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

/** A footer link column; on phones its heading is a disclosure button and the links start folded. */
function FooterColumn({ title, collapsible, children }: { title: string; collapsible: boolean; children: ReactNode }) {
  const [open, setOpen] = useState(false);
  const id = useId();
  const expanded = !collapsible || open;
  return (
    <nav className={styles.col} aria-label={title}>
      <h2 className={styles.colTitle}>
        {collapsible ? (
          <button type="button" className={styles.colToggle} aria-expanded={open} aria-controls={id} onClick={() => setOpen(!open)}>
            {title}
            <CaretDown size={16} aria-hidden className={styles.colCaret} />
          </button>
        ) : (
          title
        )}
      </h2>
      <ul id={id} className={styles.colList} hidden={!expanded}>
        {children}
      </ul>
    </nav>
  );
}

export interface FooterViewProps {
  config: SiteConfig | undefined;
  locale?: string;
  year?: number;
}

/** Footer per design; social links, phone, legal links, locales and payment chips come from site config. */
export function FooterView({ config, locale, year = new Date().getFullYear() }: FooterViewProps) {
  const active = useLocale();
  const t = useT();
  const router = useRouter();
  const toast = useToast();
  const phone = config?.contacts.phone ?? '';
  // Phones: the link columns fold into accordions (one heading button each) so the footer stays short.
  const narrow = useMediaQuery('(max-width: 640px)');
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
    <footer className={styles.footer} data-noprint>
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
          <FooterColumn key={col.title} title={t(col.title)} collapsible={narrow}>
            {col.links.map((l) => (
              <li key={l.href + l.label}>
                {l.external ? (
                  <a className={styles.link} href={l.href}>
                    {label(t, l.label)}
                  </a>
                ) : (
                  <Link className={styles.link} href={l.href}>
                    {label(t, l.label)}
                  </Link>
                )}
              </li>
            ))}
          </FooterColumn>
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
              value={locale ?? active}
              onChange={(e) => {
                if (!isLocale(e.target.value)) return;
                document.cookie = localeCookie(e.target.value);
                router.refresh();
              }}
            >
              {LOCALES.map((l) => (
                <option key={l.code} value={l.code} lang={l.code} dir={l.dir}>
                  {l.name}
                </option>
              ))}
            </select>
            <span>{t('footer.market', { currency: config?.market.currency ?? 'USD', country: config?.market.country ?? 'United States' })}</span>
          </label>
          <nav aria-label={t('footer.legal')}>
            <ul className={styles.legal}>
              {footerLegal(config).map(([key, msg]) => (
                <li key={key}>
                  <Link className={styles.legalLink} href={legalHref(key)}>
                    {t(msg)}
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

export function Footer() {
  const { data } = useQuery(siteQueries.config(browserApi()));
  return <FooterView config={data} />;
}
