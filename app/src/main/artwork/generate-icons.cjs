// Developer tool only. Requires Node.js + sharp; it is not part of the Android build.
const fs = require('node:fs');
const path = require('node:path');
const sharp = require('sharp');

const res = path.join(__dirname, '../res');
const svg = fs.readFileSync(path.join(__dirname, 'bilibili.svg'), 'utf8');
const paths = [...svg.matchAll(/<path\b[^>]*\/>/g)].map(match => match[0]);
const foreground = paths.slice(1).join('');
const pink = '#EC5D85';
const wrap = inner => Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">${inner}</svg>`);
const densities = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };

async function main() {
  const regular = wrap(`<rect width="1024" height="1024" fill="${pink}"/><g transform="translate(61.44 61.44) scale(.88)">${foreground}</g>`);
  const round = wrap(`<circle cx="512" cy="512" r="512" fill="${pink}"/><g transform="translate(107.52 107.52) scale(.79)">${foreground}</g>`);
  const adaptiveForeground = wrap(`<g transform="translate(170.667 170.667) scale(.666667)">${foreground}</g>`);
  for (const [density, scale] of Object.entries(densities)) {
    const dir = path.join(res, `mipmap-${density}`);
    fs.mkdirSync(dir, { recursive: true });
    await sharp(regular).resize(48 * scale).png().toFile(path.join(dir, 'ic_launcher.png'));
    await sharp(round).resize(48 * scale).png().toFile(path.join(dir, 'ic_launcher_round.png'));
    await sharp(adaptiveForeground).resize(108 * scale).png().toFile(path.join(dir, 'ic_launcher_foreground.png'));
  }

  const vectorPaths = paths.slice(1).map(p => {
    const d = p.match(/\bd="([^"]+)"/)[1];
    const fill = p.match(/\bfill="([^"]+)"/)[1];
    return `        <path android:fillColor="${fill}" android:pathData="${d}" />`;
  }).join('\n');
  fs.writeFileSync(path.join(res, 'drawable/ic_launcher_foreground.xml'),
    `<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n    <group android:scaleX="0.0703125" android:scaleY="0.0703125" android:translateX="18" android:translateY="18">\n${vectorPaths}\n    </group>\n</vector>\n`);

  // Fill the TV tile edge to edge. The launcher applies its own rounded rectangle mask.
  const logo = await sharp(wrap(foreground)).resize(620).png().toBuffer();
  await sharp({ create: { width: 1280, height: 720, channels: 4, background: pink } })
    .composite([{ input: logo, left: 330, top: 50 }])
    .png().toFile(path.join(res, 'drawable-nodpi/tv_banner.png'));
  await sharp(regular).resize(512).png().toFile(path.join(__dirname, 'launcher-preview.png'));
}

main().catch(error => { console.error(error); process.exitCode = 1; });
