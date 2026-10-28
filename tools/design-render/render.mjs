// Draws a docs/design/**/*.dc.html mockup to a PNG, headless, so a build run can put the design beside the app.
// Usage: node tools/design-render/render.mjs <mockup.dc.html> <out.png> [width] [--click "Text"]...
// The mockups load ./support.js (the design tool's runtime, not in the repo); this serves our stand-in instead.
// Web fonts are blocked (no network needed); the page falls back to a system sans.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const here = path.dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const clicks = [];
const pos = [];
for (let i = 0; i < args.length; i++) { if (args[i] === '--click') clicks.push(args[++i]); else pos.push(args[i]); }
const [input, out, widthArg] = pos;
if (!input || !out) { console.error('usage: render.mjs <mockup.dc.html> <out.png> [width] [--click Text]'); process.exit(2); }
const file = path.resolve(input);
const root = path.dirname(file);
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.svg': 'image/svg+xml' };
const server = http.createServer((req, res) => {
  const p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
  const f = path.basename(p) === 'support.js' ? path.join(here, 'support.js') : path.join(root, path.normalize(p));
  if (!f.startsWith(root) && !f.startsWith(here)) { res.writeHead(403); return res.end(); }
  fs.readFile(f, (e, b) => { if (e) { res.writeHead(404); return res.end(); } res.writeHead(200, { 'content-type': types[path.extname(f)] || 'application/octet-stream' }); res.end(b); });
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const port = server.address().port;
const browser = await chromium.launch();
try {
  let width = Number(widthArg) || 0;
  if (!width) { const m = fs.readFileSync(file, 'utf8').match(/"\$preview":\{"width":(\d+)/); width = m ? Number(m[1]) : 390; }
  const page = await browser.newPage({ viewport: { width, height: 900 }, deviceScaleFactor: 2, reducedMotion: 'reduce', colorScheme: 'dark' });
  await page.route(/^https?:\/\/(?!127\.0\.0\.1)/, (r) => r.abort());
  page.on('pageerror', (e) => console.error('page error:', e.message));
  await page.goto(`http://127.0.0.1:${port}/${path.basename(file)}`);
  await page.waitForFunction(() => window.__dcReady === true, null, { timeout: 15000 });
  for (const c of clicks) { await page.getByText(c, { exact: true }).first().click(); await page.waitForTimeout(150); }
  await page.waitForTimeout(300);
  await page.screenshot({ path: out, fullPage: true });
  console.log(`${out} (${width} px wide)`);
} finally {
  await browser.close();
  server.close();
}
