// Renders the PocketCash app icon (a wallet with bills peeking out, on emerald) for both stores. No dependencies.
//   -> ios/PocketCash/Assets.xcassets/AppIcon.appiconset/icon-1024.png   (1024, opaque, iOS rounds the corners)
//   -> android/app/src/main/res/drawable-nodpi/ic_launcher_fg.png         (432, transparent, adaptive foreground)
//   -> android/app/src/main/res/drawable-nodpi/ic_launcher_mono.png       (432, alpha only, themed icon)
//   -> art/icon-1024.png, art/play-icon-512.png                           (store listings)
// The adaptive icon's background is the color `ic_launcher_background` (#059669) in res/values/colors.xml.
// Usage: node tools/make_icon.js
const fs = require('fs');
const path = require('path');
const { encode, resize } = require('./png');

const ROOT = path.join(__dirname, '..');
const hex = (h, a = 1) => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16), a];

// Signed distance to a rounded box centered at the origin with half-size (a, b) and corner radius r.
function sdBox(x, y, a, b, r) {
  const qx = Math.abs(x) - (a - r), qy = Math.abs(y) - (b - r);
  return Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - r;
}
// Shapes in a 1024 design space. rot is in degrees. kind: 'box' | 'circle'. erase: punch transparency (mono only).
function hit(s, x, y) {
  let dx = x - s.cx, dy = y - s.cy;
  if (s.rot) {
    const t = -s.rot * Math.PI / 180, c = Math.cos(t), n = Math.sin(t);
    [dx, dy] = [dx * c - dy * n, dx * n + dy * c];
  }
  if (s.kind === 'circle') return Math.hypot(dx, dy) <= s.r;
  return sdBox(dx, dy, s.a, s.b, s.rad) <= (s.grow || 0);
}

// Foreground artwork, painted in order.
const art = [
  // Two bills fanned out behind the wallet.
  { kind: 'box', cx: 470, cy: 372, a: 270, b: 132, rad: 30, rot: -9, c: hex('#a7f3d0') },
  { kind: 'box', cx: 470, cy: 372, a: 230, b: 96, rad: 18, rot: -9, c: hex('#6ee7b7') },
  { kind: 'circle', cx: 470, cy: 372, r: 54, c: hex('#a7f3d0') },
  { kind: 'box', cx: 560, cy: 350, a: 270, b: 132, rad: 30, rot: 7, c: hex('#ecfdf5') },
  { kind: 'box', cx: 560, cy: 350, a: 232, b: 96, rad: 18, rot: 7, c: hex('#d1fae5') },
  { kind: 'circle', cx: 560, cy: 350, r: 54, c: hex('#ecfdf5') },
  { kind: 'circle', cx: 560, cy: 350, r: 30, c: hex('#34d399') },
  // Wallet body, its top lip and the clasp strap.
  { kind: 'box', cx: 512, cy: 612, a: 360, b: 236, rad: 76, eraseGrow: 18 },
  { kind: 'box', cx: 512, cy: 612, a: 360, b: 236, rad: 76, c: hex('#064e3b') },
  { kind: 'box', cx: 512, cy: 404, a: 330, b: 30, rad: 30, c: hex('#ffffff', 0.10), clipTo: 'body' },
  { kind: 'box', cx: 742, cy: 612, a: 170, b: 92, rad: 60, c: hex('#047857') },
  { kind: 'circle', cx: 780, cy: 612, r: 38, c: hex('#fbbf24'), mono: 'erase' },
  { kind: 'circle', cx: 768, cy: 600, r: 13, c: hex('#fde68a'), mono: 'skip' },
];
const body = art.find(s => s.c && s.c[0] === 0x06 && s.c[2] === 0x3b);

function paint({ size, scale, offset, background, mono }) {
  const SS = 4, data = Buffer.alloc(size * size * 4);
  for (let py = 0; py < size; py++) for (let px = 0; px < size; px++) {
    let R = 0, G = 0, B = 0, A = 0;
    for (let sy = 0; sy < SS; sy++) for (let sx = 0; sx < SS; sx++) {
      const x = (px + (sx + 0.5) / SS) / scale - offset, y = (py + (sy + 0.5) / SS) / scale - offset;
      let c = background ? background(x, y) : [0, 0, 0], a = background ? 1 : 0;
      for (const s of art) {
        if (s.eraseGrow != null) {
          // A gap around the wallet, so the themed icon reads as bills and a wallet, not one blob.
          if (mono && sdBox(x - s.cx, y - s.cy, s.a, s.b, s.rad) <= s.eraseGrow) a = 0;
          continue;
        }
        if (!hit(s, x, y)) continue;
        if (s.clipTo === 'body' && !hit(body, x, y)) continue;
        if (mono) {
          if (s.mono === 'skip') continue;
          if (s.mono === 'erase') a = 0;
          else if (s.c[3] >= 1) { c = [255, 255, 255]; a = 1; }
          continue;
        }
        const k = s.c[3];
        c = c.map((v, i) => v + k * (s.c[i] - v));
        a = a + k * (1 - a);
      }
      R += c[0] * a; G += c[1] * a; B += c[2] * a; A += a;
    }
    const o = (py * size + px) * 4;
    data[o + 3] = Math.round(A / (SS * SS) * 255);
    for (const [i, v] of [R, G, B].entries()) data[o + i] = A > 0 ? Math.round(v / A) : 0;
  }
  return { width: size, height: size, data };
}

// Emerald gradient with a soft light in the top-right corner, for the full-bleed store icon.
const emerald = (x, y) => {
  const k = Math.min(1, Math.max(0, (x * 0.35 + y) / 1300));
  let c = [16 + (4 - 16) * k, 185 + (120 - 185) * k, 129 + (87 - 129) * k];
  const glow = Math.max(0, 1 - Math.hypot(x - 860, y - 140) / 620) * 0.28;
  return c.map(v => v + glow * (255 - v));
};

function write(rel, img, opts) {
  const file = path.join(ROOT, rel);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, encode(img, opts));
  console.log(`wrote ${rel} (${img.width}x${img.height})`);
}

const full = paint({ size: 1024, scale: 1, offset: 0, background: emerald });
write('ios/PocketCash/Assets.xcassets/AppIcon.appiconset/icon-1024.png', full, { alpha: false });
write('art/icon-1024.png', full, { alpha: false });
write('art/play-icon-512.png', resize(full, 512, 512), { alpha: true });

// Adaptive icon: 108 dp canvas, of which the middle 66 dp is always visible. Fit the 1024 art into ~64 dp.
const FG = 432, artScale = (FG * 64 / 108) / 1024 * 1.12, artOffset = (FG / artScale - 1024) / 2;
write('android/app/src/main/res/drawable-nodpi/ic_launcher_fg.png', paint({ size: FG, scale: artScale, offset: artOffset }), { alpha: true });
write('android/app/src/main/res/drawable-nodpi/ic_launcher_mono.png', paint({ size: FG, scale: artScale, offset: artOffset, mono: true }), { alpha: true });
