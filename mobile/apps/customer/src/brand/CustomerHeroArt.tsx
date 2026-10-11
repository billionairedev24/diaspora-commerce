/* Default welcome art for the customer app, drawn in the brand style (no stock photo needed): a Sunday table — the
   gold AfroTransact bag full of greens and plantains, a pot of jollof, spice jars and a strip of kente-style cloth,
   on a warm gold-to-black glow. Token colours only. A photo under assets/hero replaces it (heroPhoto.ts). */
import Svg, { Circle, Defs, Ellipse, G, LinearGradient, Path, RadialGradient, Rect, Stop } from 'react-native-svg';

import { t } from '@/theme';
import { BAG_PATH, MARK_AFRICA_PATH, MARK_MADAGASCAR_PATH } from '@/ui/Logo';

const KENTE = [t.gold['500'], t.ink['1000'], t.green['600'], t.gold['500'], t.red['600'], t.ink['1000']];

function Kente({ y, offset }: { y: number; offset: number }) {
  return (
    <G>
      {Array.from({ length: 14 }, (_, i) => (
        <G key={i}>
          <Rect x={-80 + i * 40} y={y} width={40} height={34} fill={KENTE[(i + offset) % KENTE.length]} />
          <Rect x={-80 + i * 40 + 6} y={y + 8} width={28} height={3} fill={t.gold['200']} opacity={0.55} />
          <Rect x={-80 + i * 40 + 6} y={y + 23} width={28} height={3} fill={t.gold['200']} opacity={0.55} />
        </G>
      ))}
    </G>
  );
}

function Leaf({ d, fill }: { d: string; fill: string }) {
  return <Path d={d} fill={fill} />;
}

export function CustomerHeroArt() {
  // The bag mark (103 × 118 units) at 1.7×, centred at (150, 360).
  const k = 1.7;
  const bagX = 150 - 51.57 * k;
  const bagY = 360 - 58.8 * k;
  return (
    <Svg width="100%" height="100%" viewBox="0 0 400 640" preserveAspectRatio="xMidYMid slice">
      <Defs>
        <LinearGradient id="sky" x1="0" y1="0" x2="0" y2="1">
          <Stop offset="0" stopColor={t.gold['900']} />
          <Stop offset="0.55" stopColor={t.ink['900']} />
          <Stop offset="1" stopColor={t.ink['1000']} />
        </LinearGradient>
        <RadialGradient id="glow" cx="0.5" cy="0.5" r="0.5">
          <Stop offset="0" stopColor={t.gold['400']} stopOpacity={0.5} />
          <Stop offset="1" stopColor={t.gold['400']} stopOpacity={0} />
        </RadialGradient>
      </Defs>
      <Rect width="400" height="640" fill="url(#sky)" />
      <Circle cx="200" cy="300" r="230" fill="url(#glow)" />

      {/* Cloth band behind the table, below the logo area */}
      <G rotation={-7} origin="200, 240" opacity={0.85}>
        <Kente y={206} offset={0} />
        <Kente y={240} offset={3} />
      </G>

      {/* Table */}
      <Ellipse cx="200" cy="470" rx="290" ry="48" fill={t.ink['800']} />
      <Rect x="-20" y="470" width="440" height="200" fill={t.ink['900']} />

      {/* Groceries rising out of the bag (drawn first: the bag covers their lower half) */}
      <Leaf d="M92 312 C70 262 84 222 118 206 C124 246 116 282 92 312 Z" fill={t.green['500']} />
      <Leaf d="M112 312 C108 256 132 220 166 214 C160 256 142 288 112 312 Z" fill={t.green['400']} />
      <Leaf d="M128 314 C140 270 172 248 204 252 C188 284 162 306 128 314 Z" fill={t.green['600']} />
      <Path d="M150 318 C160 268 196 226 246 214 C252 222 250 228 244 232 C206 246 180 278 170 320 Z" fill={t.gold['400']} stroke={t.gold['700']} strokeWidth={2} />
      <Path d="M166 320 C182 276 214 246 258 240 C262 248 258 254 252 256 C220 266 196 290 186 322 Z" fill={t.gold['300']} stroke={t.gold['700']} strokeWidth={2} />
      <Path d="M244 214 L256 208 L258 216 Z M256 240 L268 236 L266 246 Z" fill={t.ink['800']} />
      <Ellipse cx="120" cy="300" rx="34" ry="13" fill={t.sand['700']} rotation={-28} origin="120, 300" />

      {/* The AfroTransact bag */}
      <G transform={`translate(${bagX} ${bagY}) scale(${k})`}>
        <Path d={BAG_PATH} fill={t.gold['500']} />
        <Path d={MARK_AFRICA_PATH} fill={t.ink['900']} />
        <Path d={MARK_MADAGASCAR_PATH} fill={t.ink['900']} />
      </G>

      {/* Spice jars */}
      <Rect x="232" y="404" width="26" height="48" rx="6" fill={t.sand['100']} />
      <Rect x="230" y="396" width="30" height="12" rx="4" fill={t.red['600']} />
      <Rect x="236" y="424" width="18" height="18" rx="3" fill={t.red['500']} opacity={0.75} />
      <Rect x="264" y="416" width="22" height="38" rx="5" fill={t.sand['200']} />
      <Rect x="262" y="408" width="26" height="11" rx="4" fill={t.green['600']} />

      {/* Pot of jollof */}
      <Rect x="292" y="350" width="22" height="12" rx="6" fill={t.ink['600']} />
      <Rect x="384" y="350" width="22" height="12" rx="6" fill={t.ink['600']} />
      <Path d="M300 346 L398 346 L392 446 C390 458 380 466 366 466 L332 466 C318 466 308 458 306 446 Z" fill={t.ink['700']} />
      <Ellipse cx="349" cy="346" rx="52" ry="13" fill={t.ink['600']} />
      <Ellipse cx="349" cy="342" rx="44" ry="10" fill={t.red['500']} />
      <Circle cx="332" cy="340" r="3" fill={t.gold['300']} />
      <Circle cx="356" cy="338" r="2.5" fill={t.green['400']} />
      <Circle cx="368" cy="343" r="3" fill={t.gold['300']} />
      <Circle cx="344" cy="345" r="2.5" fill={t.green['400']} />
      <Path d="M330 320 C320 304 340 296 330 278 M350 316 C340 298 362 290 352 270 M370 320 C360 304 380 296 370 280" stroke={t.gold['100']} strokeWidth={3} strokeLinecap="round" fill="none" opacity={0.45} />

      {/* Bowl of greens */}
      <Path d="M18 456 C22 486 44 498 70 498 C96 498 118 486 122 456 Z" fill={t.sand['200']} />
      <Ellipse cx="70" cy="456" rx="52" ry="10" fill={t.green['600']} />
      <Circle cx="56" cy="452" r="5" fill={t.green['400']} />
      <Circle cx="80" cy="451" r="4" fill={t.red['500']} />
    </Svg>
  );
}
