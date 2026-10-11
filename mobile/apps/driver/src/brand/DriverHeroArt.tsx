/* Default welcome art for the driver app, drawn in the brand style (no stock photo needed): dusk on a city street, the
   gold AfroTransact van at the kerb and a courier in a gold vest handing a parcel to a customer at a lit doorway.
   Token colours only. A photo or a short clip under assets/hero replaces it (heroPhoto.ts, heroVideo.ts). */
import Svg, { Circle, Defs, G, LinearGradient, Path, Polygon, Rect, Stop } from 'react-native-svg';

import { t } from '@/theme';
import { BAG_PATH, MARK_AFRICA_PATH, MARK_MADAGASCAR_PATH } from '@/ui/Logo';

const BUILDINGS = [
  { x: 0, w: 70, h: 150 },
  { x: 64, w: 54, h: 210 },
  { x: 112, w: 80, h: 120 },
  { x: 186, w: 46, h: 240 },
  { x: 226, w: 74, h: 170 },
  { x: 294, w: 60, h: 205 },
  { x: 348, w: 70, h: 140 }
];
const BASE = 340;

function Windows({ x, w, h }: { x: number; w: number; h: number }) {
  const cells = [];
  for (let row = 0; row * 22 + 18 < h - 10; row++) {
    for (let col = 0; col * 16 + 10 < w - 8; col++) {
      // A fixed pattern of lit windows (deterministic, no randomness).
      if ((row * 7 + col * 3 + x) % 5 === 0) cells.push(<Rect key={`${row}-${col}`} x={x + 8 + col * 16} y={BASE - h + 14 + row * 22} width={8} height={10} fill={t.gold['300']} opacity={0.75} />);
    }
  }
  return <G>{cells}</G>;
}

/** tall: the welcome hero (about square); wide: the Today card (short and wide), cropped to the hand-over. */
const VIEW = { tall: '0 100 400 420', wide: '0 130 400 240' } as const;

export function DriverHeroArt({ crop = 'tall' }: { crop?: keyof typeof VIEW }) {
  const k = 0.42;
  return (
    <Svg width="100%" height="100%" viewBox={VIEW[crop]} preserveAspectRatio="xMidYMid slice">
      <Defs>
        <LinearGradient id="dusk" x1="0" y1="0" x2="0" y2="1">
          <Stop offset="0" stopColor={t.ink['1000']} />
          <Stop offset="0.3" stopColor={t.ink['900']} />
          <Stop offset="0.52" stopColor={t.gold['800']} />
          <Stop offset="0.6" stopColor={t.gold['600']} />
        </LinearGradient>
        <LinearGradient id="spill" x1="0" y1="0" x2="0" y2="1">
          <Stop offset="0" stopColor={t.gold['200']} stopOpacity={0.35} />
          <Stop offset="1" stopColor={t.gold['200']} stopOpacity={0} />
        </LinearGradient>
      </Defs>
      <Rect y="0" width="400" height="640" fill="url(#dusk)" />
      <Circle cx="96" cy="300" r="48" fill={t.gold['400']} opacity={0.9} />

      {/* Skyline */}
      {BUILDINGS.map((b) => (
        <G key={b.x}>
          <Rect x={b.x} y={BASE - b.h} width={b.w} height={b.h} fill={t.ink['900']} />
          <Windows {...b} />
        </G>
      ))}

      {/* Street */}
      <Rect x="0" y={BASE} width="400" height="300" fill={t.ink['800']} />
      <Rect x="0" y={BASE} width="400" height="18" fill={t.ink['700']} />
      <Rect x="0" y="424" width="400" height="6" fill={t.ink['900']} />
      {[10, 90, 170, 250, 330].map((x) => <Rect key={x} x={x} y="470" width="48" height="6" rx="3" fill={t.gold['500']} opacity={0.8} />)}

      {/* Doorway with warm light */}
      <Rect x="322" y="176" width="78" height={BASE - 176 + 4} fill={t.ink['1000']} />
      <Rect x="332" y="186" width="68" height={BASE - 186} fill={t.gold['200']} opacity={0.85} />
      <Polygon points={`332,${BASE + 18} 400,${BASE + 18} 400,430 270,430`} fill="url(#spill)" />

      {/* Van */}
      <G>
        <Path d="M-40 248 L150 248 C164 248 170 256 176 268 L198 312 C202 320 204 328 204 338 L204 356 L-40 356 Z" fill={t.gold['500']} />
        <Path d="M152 262 L170 262 L190 306 L152 306 Z" fill={t.ink['900']} />
        <Rect x="-40" y="320" width="244" height="10" fill={t.gold['600']} />
        <Rect x="192" y="330" width="12" height="10" rx="3" fill={t.gold['100']} />
        <G transform={`translate(${46 - 51.57 * k} ${292 - 58.8 * k}) scale(${k})`}>
          <Path d={BAG_PATH} fill={t.ink['1000']} />
          <Path d={MARK_AFRICA_PATH} fill={t.gold['500']} />
          <Path d={MARK_MADAGASCAR_PATH} fill={t.gold['500']} />
        </G>
        <Circle cx="34" cy="358" r="24" fill={t.ink['1000']} />
        <Circle cx="34" cy="358" r="10" fill={t.ink['400']} />
        <Circle cx="156" cy="358" r="24" fill={t.ink['1000']} />
        <Circle cx="156" cy="358" r="10" fill={t.ink['400']} />
      </G>

      {/* Customer at the door, reaching for the parcel */}
      <G>
        <Rect x="338" y="282" width="16" height="62" rx="6" fill={t.ink['700']} />
        <Rect x="356" y="282" width="16" height="62" rx="6" fill={t.ink['700']} />
        <Path d="M334 222 C334 208 344 202 356 202 C368 202 378 208 378 222 L378 290 L334 290 Z" fill={t.red['600']} />
        <Path d="M338 230 C318 236 304 244 296 252 L302 260 C312 254 326 248 344 244 Z" fill={t.sand['700']} />
        <Circle cx="356" cy="184" r="17" fill={t.sand['700']} />
        <Path d="M339 182 C339 166 350 160 358 160 C368 160 374 168 373 178 C366 172 352 172 339 182 Z" fill={t.ink['1000']} />
      </G>

      {/* Courier in the gold vest, handing over the parcel */}
      <G>
        <Rect x="218" y="284" width="18" height="64" rx="7" fill={t.ink['900']} />
        <Rect x="240" y="284" width="18" height="64" rx="7" fill={t.ink['900']} />
        <Rect x="214" y="342" width="24" height="10" rx="5" fill={t.ink['1000']} />
        <Rect x="238" y="342" width="24" height="10" rx="5" fill={t.ink['1000']} />
        <Path d="M212 222 C212 206 224 198 238 198 C252 198 264 206 264 222 L264 292 L212 292 Z" fill={t.ink['700']} />
        <Path d="M216 214 C220 206 228 202 238 202 C248 202 256 206 260 214 L260 290 L216 290 Z" fill={t.gold['500']} />
        <Rect x="216" y="254" width="44" height="7" fill={t.gold['100']} />
        <Path d="M252 226 C266 232 278 238 288 246 L282 256 C270 250 258 244 246 238 Z" fill={t.sand['700']} />
        <Path d="M224 230 C238 240 256 250 280 258 L278 268 C252 262 232 252 218 242 Z" fill={t.sand['700']} />
        <Circle cx="238" cy="180" r="18" fill={t.sand['700']} />
        <Path d="M219 176 C219 160 230 154 240 154 C252 154 258 162 258 172 L219 176 Z" fill={t.ink['1000']} />
        <Path d="M240 170 L268 172 L266 178 L240 176 Z" fill={t.gold['500']} />
      </G>

      {/* The parcel */}
      <G>
        <Rect x="272" y="232" width="46" height="38" rx="3" fill={t.sand['200']} />
        <Rect x="292" y="232" width="7" height="38" fill={t.gold['600']} />
        <Rect x="272" y="232" width="46" height="6" fill={t.sand['100']} />
      </G>
    </Svg>
  );
}
