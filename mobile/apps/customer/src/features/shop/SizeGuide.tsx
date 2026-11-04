/* Option chips (Size, Color ...) and the size guide of a sized product. The rules (which chip is available, which row is the
   shopper's, cm / in) are the shared helpers of @afrotransact/api-client (size-charts), the same ones the web storefront uses. */
import {
  guideCell,
  guideUnits,
  initialGuideUnit,
  type PickerDimension,
  type ProductDetail,
  type ProductSizeChart,
  selectedRowIndex,
  SIZE_DIMENSION,
  type SizeChartUnit,
  variantForSize
} from '@afrotransact/api-client';
import { useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { secureStorage } from '@/auth/secureStorage';
import { type MessageKey, useT } from '@/i18n';
import { c, sp, t } from '@/theme';
import { AppText, Button } from '@/ui/primitives';
import { Sheet } from '@/ui/Sheet';

const UNIT_KEY = 'at_size_guide_unit';

const FIT_KEYS: Record<NonNullable<ProductSizeChart['fitNote']>, MessageKey> = {
  RUNS_SMALL: 'cu.sizeGuide.fit.RUNS_SMALL',
  TRUE_TO_SIZE: 'cu.sizeGuide.fit.TRUE_TO_SIZE',
  RUNS_LARGE: 'cu.sizeGuide.fit.RUNS_LARGE'
};

type Variants = ProductDetail['product']['variants'];

/** One row of chips per option. A chip opens the variant it names; `onOpenGuide` puts the "Size guide" link beside Size. */
export function OptionChips({ dimensions, onPick, onOpenGuide }: { dimensions: PickerDimension[]; onPick: (atin: string) => void; onOpenGuide?: () => void }) {
  const tr = useT();
  return (
    <View style={{ gap: sp['3'] }}>
      {dimensions.map((d) => (
        <View key={d.name} style={{ gap: sp['2'] }}>
          <View style={styles.head}>
            <AppText variant="bodyStrong">{d.name}</AppText>
            <AppText variant="small" color={c.textMuted} style={{ flex: 1 }}>
              {d.chips.find((x) => x.state === 'selected')?.value}
            </AppText>
            {d.name === SIZE_DIMENSION && onOpenGuide ? (
              <Pressable accessibilityRole="link" onPress={onOpenGuide} hitSlop={8} style={styles.link}>
                <AppText variant="small" color={c.link}>
                  {tr('cu.sizeGuide.open')}
                </AppText>
              </Pressable>
            ) : null}
          </View>
          <View style={styles.chips}>
            {d.chips.map((chip) => {
              const off = chip.state === 'unavailable' || (chip.state !== 'selected' && !chip.atin);
              const label = chip.state === 'elsewhere' ? tr('cu.pdp.chip.elsewhere', { value: chip.value }) : off ? tr('cu.pdp.chip.unavailable', { value: chip.value }) : chip.value;
              return (
                <Pressable
                  key={chip.value}
                  accessibilityRole="button"
                  accessibilityLabel={label}
                  accessibilityState={{ selected: chip.state === 'selected', disabled: off }}
                  disabled={off || chip.state === 'selected'}
                  onPress={() => chip.atin && onPick(chip.atin)}
                  style={[styles.chip, chip.state === 'selected' && styles.chipOn, chip.state === 'elsewhere' && styles.chipDashed, off && styles.chipOff]}
                >
                  <AppText variant="small" color={off ? c.textMuted : c.text} style={{ fontWeight: chip.state === 'selected' ? t.fontWeight.bold : t.fontWeight.regular, textDecorationLine: off ? 'line-through' : 'none' }}>
                    {chip.value}
                  </AppText>
                </Pressable>
              );
            })}
          </View>
        </View>
      ))}
    </View>
  );
}

/** The seller's chart in a bottom sheet: cm / in, the shopper's size highlighted, "Select" for the other sizes. */
export function SizeGuideSheet({
  visible,
  onClose,
  chart,
  size,
  variants,
  onPick
}: {
  visible: boolean;
  onClose: () => void;
  chart: ProductSizeChart;
  size: string | null;
  variants: Variants;
  onPick: (atin: string) => void;
}) {
  const tr = useT();
  const [unit, setUnit] = useState<SizeChartUnit>(chart.unitSystem);
  const units = guideUnits(chart);
  const selected = selectedRowIndex(chart, size);

  // The unit the shopper chose last time, when this chart offers it.
  useEffect(() => {
    let live = true;
    secureStorage
      .get(UNIT_KEY)
      .then((v) => live && setUnit(initialGuideUnit(chart, v)))
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, [chart]);

  const choose = (u: SizeChartUnit) => {
    setUnit(u);
    secureStorage.set(UNIT_KEY, u).catch(() => undefined);
  };
  const unitLabel = (u: SizeChartUnit) => tr(u === 'CM' ? 'cu.sizeGuide.unit.CM' : 'cu.sizeGuide.unit.IN');

  return (
    <Sheet visible={visible} onClose={onClose}>
      <AppText variant="heading" accessibilityRole="header">
        {tr('cu.sizeGuide.title')}
      </AppText>
      <AppText variant="small" color={c.textMuted}>
        {chart.name}
      </AppText>
      {units.length > 1 ? (
        <View accessibilityRole="radiogroup" accessibilityLabel={tr('cu.sizeGuide.units')} style={styles.chips}>
          {units.map((u) => (
            <Pressable key={u} accessibilityRole="radio" accessibilityState={{ selected: u === unit }} onPress={() => choose(u)} style={[styles.chip, u === unit && styles.chipOn]}>
              <AppText variant="small" style={{ fontWeight: u === unit ? t.fontWeight.bold : t.fontWeight.regular }}>
                {unitLabel(u)}
              </AppText>
            </Pressable>
          ))}
        </View>
      ) : null}
      {chart.fitNote ? <AppText variant="small">{tr(FIT_KEYS[chart.fitNote])}</AppText> : null}
      {size && selected < 0 ? <AppText variant="small">{tr('cu.sizeGuide.notInChart', { size })}</AppText> : null}
      <ScrollView style={{ maxHeight: 380 }} contentContainerStyle={{ gap: sp['1'] }}>
        <View style={styles.row}>
          <AppText variant="label" color={c.textMuted} style={styles.sizeCell}>
            {tr('cu.sizeGuide.size')}
          </AppText>
          {chart.columns.map((col) => (
            <AppText key={col.key} variant="label" color={c.textMuted} style={styles.cell}>
              {col.label}
            </AppText>
          ))}
        </View>
        {chart.rows.map((r, i) => {
          const target = i === selected ? undefined : variantForSize(variants, r.size);
          return (
            <View key={r.size} style={[styles.row, i === selected && styles.rowOn]} accessible accessibilityState={{ selected: i === selected }}>
              <AppText variant="bodyStrong" style={styles.sizeCell}>
                {r.size}
                {i === selected ? ` ${tr('cu.sizeGuide.yourSize')}` : ''}
              </AppText>
              {chart.columns.map((col) => (
                <AppText key={col.key} variant="small" style={styles.cell}>
                  {guideCell(chart, r, col.key, unit)}
                </AppText>
              ))}
              {i === selected ? null : target?.atin ? (
                <Button
                  kind="muted"
                  size="md"
                  label={tr('cu.sizeGuide.selectShort')}
                  accessibilityLabel={tr('cu.sizeGuide.select', { size: r.size })}
                  onPress={() => {
                    onPick(target.atin as string);
                    onClose();
                  }}
                />
              ) : (
                <AppText variant="caption" color={c.textMuted}>
                  {tr('cu.sizeGuide.soldOut')}
                </AppText>
              )}
            </View>
          );
        })}
        {chart.howToMeasure ? (
          <View style={{ gap: sp['1'], marginTop: sp['3'] }}>
            <AppText variant="bodyStrong" accessibilityRole="header">
              {tr('cu.sizeGuide.howToMeasure')}
            </AppText>
            <AppText variant="small">{chart.howToMeasure}</AppText>
          </View>
        ) : null}
      </ScrollView>
      <Button kind="muted" label={tr('cu.sizeGuide.close')} onPress={onClose} />
    </Sheet>
  );
}

const styles = StyleSheet.create({
  head: { flexDirection: 'row', alignItems: 'baseline', gap: sp['2'] },
  link: { minHeight: 44, justifyContent: 'center' },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: sp['2'] },
  chip: { minHeight: 44, minWidth: 44, paddingHorizontal: sp['3'], alignItems: 'center', justifyContent: 'center', borderRadius: t.radius.md, borderWidth: 1, borderColor: c.border },
  chipOn: { borderColor: t.ink['1000'], borderWidth: 2, backgroundColor: c.warningBg },
  chipDashed: { borderStyle: 'dashed' },
  chipOff: { borderStyle: 'dashed', opacity: 0.6 },
  row: { flexDirection: 'row', alignItems: 'center', gap: sp['2'], paddingVertical: sp['2'], paddingHorizontal: sp['2'], borderRadius: t.radius.md },
  rowOn: { backgroundColor: c.warningBg },
  sizeCell: { width: 84 },
  cell: { flex: 1 }
});
