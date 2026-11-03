/* A native-feeling picker: a bottom sheet with a list of options (states, countries, reasons). */
import { CheckIcon as Check } from 'phosphor-react-native';
import { FlatList, Pressable, StyleSheet } from 'react-native';

import { c, sp } from '@/theme';

import { AppText } from './primitives';
import { Sheet } from './Sheet';

export interface PickerOption {
  value: string;
  label: string;
}

export function PickerSheet({ visible, title, options, value, onPick, onClose }: {
  visible: boolean;
  title: string;
  options: PickerOption[];
  value: string;
  onPick: (value: string) => void;
  onClose: () => void;
}) {
  return (
    <Sheet visible={visible} onClose={onClose}>
      <AppText variant="heading" accessibilityRole="header">{title}</AppText>
      <FlatList
        data={options}
        keyExtractor={(o) => o.value}
        style={styles.list}
        showsVerticalScrollIndicator={false}
        renderItem={({ item }) => (
          <Pressable accessibilityRole="button" accessibilityState={{ selected: item.value === value }} onPress={() => { onPick(item.value); onClose(); }} style={styles.row}>
            <AppText style={{ flex: 1 }}>{item.label}</AppText>
            {item.value === value ? <Check size={18} color={c.text} /> : null}
          </Pressable>
        )}
      />
    </Sheet>
  );
}

const styles = StyleSheet.create({
  list: { maxHeight: 360 },
  row: { flexDirection: 'row', alignItems: 'center', minHeight: 48, paddingVertical: sp['2'], borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: c.border }
});
