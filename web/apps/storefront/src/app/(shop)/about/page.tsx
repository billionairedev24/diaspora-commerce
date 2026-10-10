import { buttonClassName, CheckCircle, ImagePlaceholder, ShoppingCart, Storefront, Truck } from '@afrotransact/ui';
import { type Metadata } from 'next';
import Link from 'next/link';

import styles from '@/components/common/Content.module.css';
import { t } from '@/i18n/t';
import { siteQueries } from '@/lib/api/queries';
import { serverApi } from '@/lib/api/server';
import { getQueryClient } from '@/lib/query/getQueryClient';

export const metadata: Metadata = { title: 'About AfroTransact', description: t('about.intro') };

export default async function AboutPage() {
  const config = await getQueryClient()
    .fetchQuery(siteQueries.config(await serverApi()))
    .catch(() => null);
  const pillars = [
    { Icon: Storefront, title: t('about.pillar1Title'), text: t('about.pillar1') },
    { Icon: ShoppingCart, title: t('about.pillar2Title'), text: t('about.pillar2') },
    { Icon: Truck, title: t('about.pillar3Title'), text: t('about.pillar3') }
  ];
  const values = [1, 2, 3, 4].map((i) => ({ title: t(`about.value${i}` as 'about.value1'), text: t(`about.value${i}d` as 'about.value1d') }));
  const leaders = [t('about.ceo'), t('about.cto'), t('about.ops'), t('about.sellerSuccess')];
  return (
    <div className={styles.stack}>
      <section className={styles.split}>
        <div className={styles.col}>
          <span className={styles.kicker}>{t('about.kicker')}</span>
          <h1 className={styles.display}>{t('about.title')}</h1>
          <p className={styles.lead}>{t('about.intro')}</p>
          <div className={styles.actions}>
            <Link href="/careers" className={buttonClassName()}>
              {t('about.join')}
            </Link>
            <a href="/sell" className={buttonClassName({ variant: 'neutral' })}>
              {t('about.sell')}
            </a>
          </div>
        </div>
        <ImagePlaceholder className={styles.photo} alt={t('about.photo')} caption={t('about.photo')} ratio="4 / 3" />
      </section>
      <ul className={styles.pillars}>
        {pillars.map(({ Icon, title, text }) => (
          <li key={title} className={styles.pillar}>
            <span className={styles.pillarIcon} aria-hidden>
              <Icon />
            </span>
            <h2 className={styles.pillarTitle}>{title}</h2>
            <p className={styles.muted}>{text}</p>
          </li>
        ))}
      </ul>
      <section className={styles.split} style={{ alignItems: 'start' }}>
        <div>
          <h2 className={styles.h2}>{t('about.storyTitle')}</h2>
          <p className={styles.muted} style={{ marginBottom: 12 }}>
            {t('about.story1')}
          </p>
          <p className={styles.muted}>{t('about.story2')}</p>
        </div>
        <div>
          <h2 className={styles.h2}>{t('about.valuesTitle')}</h2>
          <ul className={styles.values}>
            {values.map((v) => (
              <li key={v.title} className={styles.value}>
                <CheckCircle weight="fill" className={styles.valueIcon} aria-hidden />
                <span>
                  <strong>{v.title}</strong> — <span className={styles.muted}>{v.text}</span>
                </span>
              </li>
            ))}
          </ul>
        </div>
      </section>
      <section aria-labelledby="leaders">
        <h2 id="leaders" className={styles.h2}>
          {t('about.leadership')}
        </h2>
        <ul className={styles.leaders}>
          {leaders.map((role) => (
            <li key={role} className={styles.leader}>
              <ImagePlaceholder alt={`${t('about.portrait')}: ${role}`} caption={t('about.portrait')} />
              <strong>{t('about.nameTbd')}</strong>
              <span className={styles.leaderRole}>{role}</span>
            </li>
          ))}
        </ul>
      </section>
      <section className={styles.press}>
        <div>
          <strong style={{ fontSize: 'var(--at-text-xl)' }}>{t('about.press')}</strong>
          <div className={styles.muted}>
            {config?.contacts.pressEmail ?? 'press@afrotransact.com'} · {config?.contacts.partnersEmail ?? 'partners@afrotransact.com'}
          </div>
        </div>
        <Link href="/careers" className={buttonClassName()}>
          {t('about.seeRoles')}
        </Link>
      </section>
    </div>
  );
}
