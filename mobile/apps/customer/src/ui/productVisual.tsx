/* Product imagery placeholders: the design shows a tinted tile with a line icon until product photos load. */
import { BowlFoodIcon as BowlFood, CookingPotIcon as CookingPot, DropHalfIcon as DropHalf, DropIcon as Drop, FireIcon as Fire, GrainsIcon as Grains, type Icon, PackageIcon as Package, PepperIcon as Pepper, TShirtIcon as TShirt } from 'phosphor-react-native';

import { c, t } from '@/theme';

const BY_KEYWORD: Array<[RegExp, Icon, string]> = [
  [/shito|pepper/i, Pepper, t.gold['100']],
  [/jollof seasoning|seasoning/i, CookingPot, t.gold['50']],
  [/jollof tray|party/i, CookingPot, t.gold['100']],
  [/palm oil|oil/i, Drop, c.dangerBg],
  [/gari/i, Grains, t.ink['50']],
  [/rice|basmati/i, BowlFood, t.green['50']],
  [/suya|spice|yaji/i, Fire, t.gold['100']],
  [/soap/i, DropHalf, t.ink['100']],
  [/kente|headwrap|wrap/i, TShirt, t.gold['50']]
];

export function productVisual(title: string): { icon: Icon; tint: string } {
  const hit = BY_KEYWORD.find(([re]) => re.test(title));
  return hit ? { icon: hit[1], tint: hit[2] } : { icon: Package, tint: t.ink['50'] };
}
