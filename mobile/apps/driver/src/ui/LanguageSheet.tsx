import { LOCALES } from '@afrotransact/i18n';
import { CheckIcon as Check } from 'phosphor-react-native';
import { Pressable, View } from 'react-native';

import { useLocale, useSetLocale } from '@/i18n';
import { c, sp, t } from '@/theme';

import { AppText } from './primitives';
import { Sheet } from './Sheet';

/** Language picker (S193): each option in its own language; Arabic switches to right-to-left after a restart. */
export function LanguageSheet({ visible, onClose, title, rtlNote }: { visible: boolean; onClose: () => void; title: string; rtlNote: string }) {
  const locale = useLocale();
  const setLocale = useSetLocale();
  return (
    <Sheet visible={visible} onClose={onClose}>
      <AppText variant="heading" accessibilityRole="header">{title}</AppText>
      <View accessibilityRole="radiogroup" style={{ gap: sp['2'] }}>
        {LOCALES.map((l) => (
          <Pressable
            key={l.code}
            accessibilityRole="radio"
            accessibilityState={{ checked: l.code === locale }}
            accessibilityLanguage={l.code}
            onPress={() => {
              setLocale(l.code);
              onClose();
            }}
            style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', minHeight: t.size.touch, paddingHorizontal: sp['3'], borderRadius: t.radius.md, borderWidth: 1, borderColor: l.code === locale ? c.text : c.border }}
          >
            <AppText style={{ writingDirection: l.dir }}>{l.name}</AppText>
            {l.code === locale ? <Check size={20} color={c.text} /> : null}
          </Pressable>
        ))}
      </View>
      <AppText variant="caption" color={c.textMuted}>{rtlNote}</AppText>
    </Sheet>
  );
}
