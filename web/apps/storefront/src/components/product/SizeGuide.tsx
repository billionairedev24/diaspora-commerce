'use client';

import {
  guideCell,
  guideUnits,
  initialGuideUnit,
  type PickerChip,
  type PickerDimension,
  type ProductSizeChart,
  selectedRowIndex,
  SIZE_DIMENSION,
  type SizeChartUnit,
  variantForSize
} from '@afrotransact/api-client';
import { Button, Sheet } from '@afrotransact/ui';
import Link from 'next/link';
import { type ReactNode, useState } from 'react';

import { type MessageKey, useT } from '@/i18n/t';
import { productHref } from '@/lib/links';

import styles from './SizeGuide.module.css';

const UNIT_KEY = 'at.sizeGuide.unit';

function rememberedUnit(): string | null {
  try {
    return window.localStorage.getItem(UNIT_KEY);
  } catch {
    return null;
  }
}

function rememberUnit(unit: SizeChartUnit) {
  try {
    window.localStorage.setItem(UNIT_KEY, unit);
  } catch {
    // Private window or blocked storage: the choice just is not kept.
  }
}

const FIT_KEYS: Record<NonNullable<ProductSizeChart['fitNote']>, MessageKey> = {
  RUNS_SMALL: 'sizeGuide.fit.RUNS_SMALL',
  TRUE_TO_SIZE: 'sizeGuide.fit.TRUE_TO_SIZE',
  RUNS_LARGE: 'sizeGuide.fit.RUNS_LARGE'
};

type PickerVariants = Parameters<typeof variantForSize>[0];

/** Size, Color ... chips. Each chip goes to the variant it names; a chip that needs another choice changed too says so. */
export function VariantChips({ dimensions, sizeGuide }: { dimensions: PickerDimension[]; sizeGuide?: ReactNode }) {
  const t = useT();
  const chip = (c: PickerChip) => {
    const label = c.state === 'elsewhere' ? t('pdp.chip.elsewhere', { value: c.value }) : c.state === 'unavailable' ? t('pdp.chip.unavailable', { value: c.value }) : c.value;
    if (c.state === 'selected') {
      return (
        <span className={styles.chip} aria-current="true" data-state="selected">
          {c.value}
        </span>
      );
    }
    if (c.state === 'unavailable' || !c.atin) {
      return (
        <span className={`${styles.chip} ${styles.chipOff}`} aria-disabled="true" data-state="unavailable" aria-label={label}>
          {c.value}
        </span>
      );
    }
    return (
      <Link href={productHref(c.atin)} className={`${styles.chip} ${c.state === 'elsewhere' ? styles.chipElsewhere : ''}`} data-state={c.state} aria-label={label} replace>
        {c.value}
      </Link>
    );
  };
  return (
    <div className={styles.picker}>
      {dimensions.map((d) => (
        <div key={d.name} role="group" aria-label={d.name} className={styles.dimension}>
          <div className={styles.dimensionHead}>
            <strong>{d.name}</strong>
            <span className={styles.chosen}>{d.chips.find((c) => c.state === 'selected')?.value}</span>
            {d.name === SIZE_DIMENSION ? sizeGuide : null}
          </div>
          <ul className={styles.chips}>
            {d.chips.map((c) => (
              <li key={c.value}>{chip(c)}</li>
            ))}
          </ul>
        </div>
      ))}
    </div>
  );
}

/** The "Size guide" link and the sheet it opens: the seller's chart in cm or in, the shopper's size highlighted. */
export function SizeGuide({
  chart,
  size,
  variants,
  onPickVariant
}: {
  chart: ProductSizeChart;
  /** The selected variant's size, or null. */
  size: string | null;
  variants: PickerVariants;
  onPickVariant: (atin: string) => void;
}) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const [unit, setUnit] = useState<SizeChartUnit>(() => initialGuideUnit(chart, rememberedUnit()));
  const units = guideUnits(chart);
  const selected = selectedRowIndex(chart, size);
  const unitLabel = (u: SizeChartUnit) => (u === 'CM' ? t('sizeGuide.unit.CM') : t('sizeGuide.unit.IN'));

  const choose = (u: SizeChartUnit) => {
    setUnit(u);
    rememberUnit(u);
  };

  return (
    <>
      <button type="button" className={`link-button ${styles.guideLink}`} onClick={() => setOpen(true)}>
        {t('sizeGuide.open')}
      </button>
      <Sheet open={open} onOpenChange={setOpen} title={t('sizeGuide.title')} description={chart.name} closeLabel={t('sizeGuide.close')} modal>
        <div className={styles.sheet}>
          <p className={styles.chartName}>{chart.name}</p>
          {units.length > 1 && (
            <div role="group" aria-label={t('sizeGuide.units')} className={styles.units}>
              {units.map((u) => (
                <button key={u} type="button" className={styles.unit} aria-pressed={u === unit} onClick={() => choose(u)}>
                  {unitLabel(u)}
                </button>
              ))}
            </div>
          )}
          {chart.fitNote && <p className={styles.fit}>{t(FIT_KEYS[chart.fitNote])}</p>}
          {size && selected < 0 && <p className={styles.fit}>{t('sizeGuide.notInChart', { size })}</p>}
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <caption className="at-visually-hidden">{t('sizeGuide.caption', { unit: unitLabel(unit) })}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('pdp.size')}</th>
                  {chart.columns.map((c) => (
                    <th key={c.key} scope="col">
                      {c.label}
                    </th>
                  ))}
                  <th scope="col">
                    <span className="at-visually-hidden">{t('sizeGuide.action')}</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {chart.rows.map((r, i) => {
                  const target = variantForSize(variants, r.size);
                  return (
                    <tr key={r.size} aria-current={i === selected ? 'true' : undefined} className={i === selected ? styles.selectedRow : undefined}>
                      <th scope="row">
                        {r.size}
                        {i === selected && <span className={styles.yours}> {t('sizeGuide.yourSize')}</span>}
                      </th>
                      {chart.columns.map((c) => (
                        <td key={c.key}>{guideCell(chart, r, c.key, unit)}</td>
                      ))}
                      <td className={styles.actionCell}>
                        {i === selected ? null : target?.atin ? (
                          <Button
                            size="sm"
                            variant="neutral"
                            onClick={() => {
                              onPickVariant(target.atin as string);
                              setOpen(false);
                            }}
                            aria-label={t('sizeGuide.select', { size: r.size })}
                          >
                            {t('sizeGuide.selectShort')}
                          </Button>
                        ) : (
                          <span className={styles.muted}>{t('sizeGuide.soldOut')}</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {chart.howToMeasure && (
            <section aria-labelledby="size-guide-measure">
              <h3 id="size-guide-measure" className={styles.h3}>
                {t('sizeGuide.howToMeasure')}
              </h3>
              <p className={styles.measure}>{chart.howToMeasure}</p>
            </section>
          )}
        </div>
      </Sheet>
    </>
  );
}
