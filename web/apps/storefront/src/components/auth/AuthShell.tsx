import { ArrowUUpLeft, ImagePlaceholder, LogoMark, SealCheck, Truck, wordmarkClassName } from '@afrotransact/ui';
import Link from 'next/link';
import { type ReactNode } from 'react';

import { t } from '@/i18n/t';

import styles from './Auth.module.css';

/** Two-column sign-in / sign-up card: advertorial panel (left) and the form (right). No header, nav or footer. */
export function AuthShell({ mode, children }: { mode: 'signin' | 'signup'; children: ReactNode }) {
  const signup = mode === 'signup';
  const points = [
    { Icon: Truck, title: t('auth.point1Title'), text: t('auth.point1') },
    { Icon: SealCheck, title: t('auth.point2Title'), text: t('auth.point2') },
    { Icon: ArrowUUpLeft, title: t('auth.point3Title'), text: t('auth.point3') }
  ];
  return (
    <div className={styles.page}>
      <div className={styles.card}>
        <aside className={styles.aside} aria-label={signup ? t('auth.adTagSignup') : t('auth.adTagSignin')}>
          <div className={styles.photo}>
            <ImagePlaceholder alt={t('auth.heroImage')} ratio="auto" />
            <div className={styles.fade} />
          </div>
          <div className={styles.adBody}>
            <span className={styles.tag}>{signup ? t('auth.adTagSignup') : t('auth.adTagSignin')}</span>
            <h2 className={styles.adHead}>{signup ? t('auth.adHeadSignup') : t('auth.adHeadSignin')}</h2>
            <p className={styles.adSub}>{signup ? t('auth.adSubSignup') : t('auth.adSubSignin')}</p>
            <ul className={styles.points}>
              {points.map(({ Icon, title, text }) => (
                <li key={title} className={styles.point}>
                  <Icon weight="fill" className={styles.pointIcon} aria-hidden />
                  <span>
                    <strong>{title}</strong>
                    <br />
                    <span className={styles.pointSub}>{text}</span>
                  </span>
                </li>
              ))}
            </ul>
          </div>
        </aside>
        <main id="main" tabIndex={-1} className={styles.formCol}>
          <Link href="/" aria-label={t('header.home')} className={styles.logo} style={{ display: 'flex', alignItems: 'center', gap: 8, textDecoration: 'none' }}>
            <LogoMark size={36} />
            <span className={wordmarkClassName}>afrotransact</span>
          </Link>
          <div className={styles.form}>{children}</div>
        </main>
      </div>
    </div>
  );
}
