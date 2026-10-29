import Link from 'next/link';

import styles from '@/components/legal/Legal.module.css';
import { getT } from '@/i18n/server';

/** /legal/<unknown>: say so, and lead to the legal center instead of the generic shop 404. */
export default async function LegalNotFound() {
  const t = await getT();
  return (
    <div className={styles.problem}>
      <h1 className={styles.title}>{t('legal.notFoundTitle')}</h1>
      <p className={styles.lede}>{t('legal.notFoundBody')}</p>
      <div className={styles.buttons}>
        <Link href="/legal" className={`${styles.btn} ${styles.btnDark}`}>
          {t('legal.allDocs')}
        </Link>
      </div>
    </div>
  );
}
