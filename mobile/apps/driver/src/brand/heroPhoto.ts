/* Optional welcome photo. This module is the fallback (no photo: the drawn brand art shows). When
   assets/hero/hero.jpg (or .png / .webp) exists, metro.config.js resolves '@/brand/heroPhoto' to that image instead,
   so dropping a photo in (tools/seed/mobile_hero.py fetches a free-licensed one) is all it takes; restart Metro with
   --clear after adding or removing it. */
import { type ImageSourcePropType } from 'react-native';

const heroPhoto: ImageSourcePropType | null = null;

export default heroPhoto;
