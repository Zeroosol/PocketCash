// Minimal PNG read/write for the art tools. No dependencies.
const zlib = require('zlib');

const SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

/** Decodes a PNG into { width, height, data } where data is 8-bit RGBA. */
function decode(buf) {
  if (buf.length < 8 || !buf.subarray(0, 8).equals(SIGNATURE)) throw new Error('not a PNG file (convert JPG/WebP to PNG first)');
  let pos = 8, ihdr, palette, trns;
  const idat = [];
  while (pos + 8 <= buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.toString('latin1', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    pos += 12 + len;
    if (type === 'IHDR') ihdr = { w: data.readUInt32BE(0), h: data.readUInt32BE(4), depth: data[8], colorType: data[9], interlace: data[12] };
    else if (type === 'PLTE') palette = data;
    else if (type === 'tRNS') trns = data;
    else if (type === 'IDAT') idat.push(data);
    else if (type === 'IEND') break;
  }
  if (!ihdr) throw new Error('PNG has no IHDR chunk');
  const { w, h, depth, colorType, interlace } = ihdr;
  if (interlace) throw new Error('interlaced PNGs are not supported; re-save it without interlacing');
  const channels = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[colorType];
  if (!channels) throw new Error(`unsupported PNG color type ${colorType}`);

  // Undo the per-row filters.
  const bits = channels * depth, bpp = Math.max(1, bits >> 3), stride = Math.ceil(w * bits / 8);
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const px = Buffer.alloc(stride * h);
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < h; y++) {
    const filter = raw[y * (stride + 1)], line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
    const out = px.subarray(y * stride, (y + 1) * stride);
    for (let i = 0; i < stride; i++) {
      const a = i >= bpp ? out[i - bpp] : 0, b = prev[i], c = i >= bpp ? prev[i - bpp] : 0;
      let v = line[i];
      if (filter === 1) v += a;
      else if (filter === 2) v += b;
      else if (filter === 3) v += (a + b) >> 1;
      else if (filter === 4) {
        const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
      }
      out[i] = v & 0xff;
    }
    prev = out;
  }

  // Expand every color type and bit depth to RGBA8.
  const maxv = (1 << depth) - 1, scale = depth < 8 ? 255 / maxv : 1;
  const sample = (row, i) => {
    if (depth === 8) return px[row + i];
    if (depth === 16) return px[row + i * 2]; // high byte
    const bit = i * depth;
    return (px[row + (bit >> 3)] >> (8 - depth - (bit & 7))) & maxv;
  };
  const data = Buffer.alloc(w * h * 4);
  for (let y = 0; y < h; y++) {
    const row = y * stride;
    for (let x = 0; x < w; x++) {
      const o = (y * w + x) * 4;
      if (colorType === 3) {
        const i = sample(row, x);
        data[o] = palette[i * 3]; data[o + 1] = palette[i * 3 + 1]; data[o + 2] = palette[i * 3 + 2];
        data[o + 3] = trns && i < trns.length ? trns[i] : 255;
      } else {
        const s = (k) => Math.round(sample(row, x * channels + k) * scale);
        if (channels <= 2) { data[o] = data[o + 1] = data[o + 2] = s(0); data[o + 3] = channels === 2 ? s(1) : 255; }
        else { data[o] = s(0); data[o + 1] = s(1); data[o + 2] = s(2); data[o + 3] = channels === 4 ? s(3) : 255; }
      }
    }
  }
  return { width: w, height: h, data };
}

const crcTable = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
const crc32 = (buf) => { let c = 0xffffffff; for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
const chunk = (type, data) => {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
};

/** Encodes RGBA8 pixels. `alpha: false` writes opaque RGB, which the App Store requires for the icon. */
function encode({ width: w, height: h, data }, { alpha = true } = {}) {
  const ch = alpha ? 4 : 3, stride = w * ch;
  const line = Buffer.alloc(stride);
  let prev = Buffer.alloc(stride);
  const rows = [];
  for (let y = 0; y < h; y++) {
    const cur = Buffer.alloc(stride);
    for (let x = 0; x < w; x++) for (let k = 0; k < ch; k++) cur[x * ch + k] = data[(y * w + x) * 4 + k];
    // Pick the filter with the smallest sum of residuals (the usual libpng heuristic).
    let best, bestScore = Infinity;
    for (let f = 0; f <= 4; f++) {
      let score = 0;
      for (let i = 0; i < stride; i++) {
        const a = i >= ch ? cur[i - ch] : 0, b = prev[i], c = i >= ch ? prev[i - ch] : 0;
        let pred = 0;
        if (f === 1) pred = a;
        else if (f === 2) pred = b;
        else if (f === 3) pred = (a + b) >> 1;
        else if (f === 4) { const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c); pred = pa <= pb && pa <= pc ? a : pb <= pc ? b : c; }
        const r = (cur[i] - pred) & 0xff;
        line[i] = r; score += r < 128 ? r : 256 - r;
      }
      if (score < bestScore) { bestScore = score; best = Buffer.concat([Buffer.from([f]), line]); }
    }
    rows.push(best);
    prev = cur;
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = alpha ? 6 : 2;
  return Buffer.concat([
    SIGNATURE,
    chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(Buffer.concat(rows), { level: 9 })), chunk('IEND', Buffer.alloc(0)),
  ]);
}

/** Catmull-Rom resample in premultiplied alpha, so edges stay crisp and fringe-free. */
function resize(img, W, H) {
  const { width: w, height: h, data } = img;
  const cubic = (x) => (x < 1 ? 1.5 * x ** 3 - 2.5 * x ** 2 + 1 : x < 2 ? -0.5 * x ** 3 + 2.5 * x ** 2 - 4 * x + 2 : 0);
  const taps = (srcN, dstN) => {
    const scale = srcN / dstN, r = Math.max(1, scale);
    return Array.from({ length: dstN }, (_, i) => {
      const c = (i + 0.5) * scale, list = [];
      let sum = 0;
      for (let j = Math.floor(c - 2 * r); j <= Math.ceil(c + 2 * r); j++) {
        const wt = cubic(Math.abs(j + 0.5 - c) / r);
        if (wt !== 0) { list.push([Math.min(srcN - 1, Math.max(0, j)), wt]); sum += wt; }
      }
      return list.map(([j, wt]) => [j, wt / sum]);
    });
  };
  const src = new Float32Array(w * h * 4);
  for (let i = 0; i < w * h; i++) {
    const a = data[i * 4 + 3] / 255;
    src[i * 4] = data[i * 4] * a; src[i * 4 + 1] = data[i * 4 + 1] * a; src[i * 4 + 2] = data[i * 4 + 2] * a; src[i * 4 + 3] = data[i * 4 + 3];
  }
  const tx = taps(w, W), ty = taps(h, H);
  const mid = new Float32Array(W * h * 4);
  for (let y = 0; y < h; y++) for (let x = 0; x < W; x++) for (const [j, wt] of tx[x]) {
    for (let k = 0; k < 4; k++) mid[(y * W + x) * 4 + k] += src[(y * w + j) * 4 + k] * wt;
  }
  const out = { width: W, height: H, data: Buffer.alloc(W * H * 4) };
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    const px = [0, 0, 0, 0];
    for (const [j, wt] of ty[y]) for (let k = 0; k < 4; k++) px[k] += mid[(j * W + x) * 4 + k] * wt;
    const a = Math.max(0, Math.min(255, px[3])), o = (y * W + x) * 4;
    out.data[o + 3] = Math.round(a);
    for (let k = 0; k < 3; k++) out.data[o + k] = a > 0 ? Math.round(Math.max(0, Math.min(a, px[k])) * 255 / a) : 0;
  }
  return out;
}

module.exports = { decode, encode, resize };
