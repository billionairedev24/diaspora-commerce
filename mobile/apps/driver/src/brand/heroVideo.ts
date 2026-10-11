/* Optional short, muted, looping welcome clip (someone driving, a hand-over at the door). This module is the
   fallback (no clip: the photo or the drawn art shows). When assets/hero/hero.mp4 exists, metro.config.js resolves
   '@/brand/heroVideo' to it instead (tools/seed/mobile_hero.py fetches a free-licensed one); restart Metro with
   --clear after adding or removing it. Reduce motion shows the still instead. */
const heroVideo: number | null = null;

export default heroVideo;
