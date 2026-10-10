#!/usr/bin/env node
// Type check (ADR-012 addendum 2026-10-10, Calm Today type pass): every text on both apps takes its size, weight,
// tracking and line height from a MekaType token (design/tokens/tokens.json `type`): no ad-hoc sizes, weights or
// bold. A token may still be copied for its colour or tabular figures (`MekaType.body.copy(color = …)`,
// `fontFeatureSettings = "tnum"`, SwiftUI's `.monospacedDigit()`). A deliberate exception (an icon sized by a font,
// a label that shrinks to fit) says `type: ok` and why on its line. Dependency-free; CI runs it with the token check.
// Usage: node design/type-check.mjs             (lists offences in every app source file; exit 1 if any)
//        node design/type-check.mjs --self-test (the rules against known lines)
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const A = 'android/app/src/main/kotlin/os/meka/android/';
const M = 'macos/MekaOS/';
const W = 'wear/app/src/main/kotlin/os/meka/wear/'; // the Galaxy Watch (Galaxy Watch, slice 2)

const KOTLIN = [
  [/\bfontWeight\s*=|\bFontWeight[.(]/g, 'an ad-hoc weight: use a MekaType token (captionStrong, metaStrong, …)'],
  [/\bfontSize\s*=/g, 'an ad-hoc size: use a MekaType token'],
  [/\b(?:letterSpacing|lineHeight)\s*=/g, 'ad-hoc tracking or line height: use a MekaType token'],
  [/\b\d+(?:\.\d+)?\.sp\b/g, 'a literal sp: use a MekaType token'],
  [/(?<![\w.])TextStyle\(/g, 'a hand-built TextStyle: use a MekaType token'],
  [/MaterialTheme\.typography/g, "Material's type scale: use a MekaType token"],
];
const SWIFT = [
  [/\.bold\(\)|\.fontWeight\(/g, 'ad-hoc bold: use a MekaType token (captionStrong, metaStrong, …)'],
  [/MekaType\.\w+\.weight\(/g, 'an ad-hoc weight on a token: use the token with that weight'],
  [/Font\.system\(|\.font\(\.system\(|\.font\(\.custom\(/g, 'an ad-hoc size: use a MekaType token'],
  [/\.font\(\.(?:largeTitle|title|title2|title3|headline|subheadline|body|callout|footnote|caption|caption2)\b/g, "the system's type scale: use a MekaType token"],
];

/** A Compose `Text(`/`BasicText(` call with no style falls back to the platform's default type: name the line. */
function unstyledTexts(text) {
  const out = [];
  const re = /(?<![\w.])(?:Basic)?Text\(/g;
  let m;
  while ((m = re.exec(text))) {
    let i = m.index + m[0].length, depth = 1;
    while (depth && i < text.length) { const c = text[i++]; if (c === '(') depth++; else if (c === ')') depth--; }
    const call = text.slice(m.index, i);
    if (!/\bstyle\s*=/.test(call)) out.push(text.slice(0, m.index).split('\n').length);
  }
  return out;
}

export function offences(path, text) {
  const kotlin = path.endsWith('.kt');
  const rules = kotlin ? KOTLIN : path.endsWith('.swift') ? SWIFT : [];
  const out = [];
  const lines = text.split('\n');
  lines.forEach((line, i) => {
    if (/type: ok/.test(line) || /^\s*import\s/.test(line)) return;
    for (const [re, why] of rules) {
      re.lastIndex = 0;
      if (re.test(line)) { out.push(`${path}:${i + 1}: ${why}\n    ${line.trim()}`); break; } // one offence a line
    }
  });
  if (kotlin) {
    for (const n of unstyledTexts(text)) {
      const line = lines[n - 1];
      if (!/type: ok/.test(line)) out.push(`${path}:${n}: a Text with no style: give it a MekaType token\n    ${line.trim()}`);
    }
  }
  return out;
}

function walk(dir, acc = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, acc);
    else if (/\.(kt|swift)$/.test(name) && !/MekaTokens\./.test(name)) acc.push(relative(root, p));
  }
  return acc;
}

if (process.argv.includes('--self-test')) {
  const cases = [
    ['a.kt', 'Text("Urgent", style = MekaType.caption.copy(fontWeight = FontWeight.SemiBold))', 1],
    ['a.kt', 'Text("x", style = MekaType.greeting.copy(fontSize = 96.sp))', 1],
    ['a.kt', 'Text("x", style = TextStyle(fontSize = 12.sp))', 1],
    ['a.kt', 'Text("x", style = MekaType.body.copy(letterSpacing = 0.1.em))', 1],
    ['a.kt', 'Text("x", style = MaterialTheme.typography.bodyLarge)', 1],
    ['a.kt', 'Text("Set")', 1],
    ['a.kt', 'BasicText(\n  "x",\n  maxLines = 1,\n)', 1],
    ['a.kt', 'Text("x", style = MekaType.captionStrong.copy(color = c))', 0],
    ['a.kt', 'Text(\n  "x",\n  style = MekaType.body,\n)', 0],
    ['a.kt', 'val s = MekaType.clock.copy(fontFeatureSettings = "tnum")', 0],
    ['a.kt', 'import androidx.compose.ui.text.font.FontWeight', 0],
    ['a.kt', 'NotificationCompat.BigTextStyle().bigText(line)', 0],
    ['a.kt', 'StepBased(minFontSize = 10.sp) // type: ok (shrinks to fit)', 0],
    ['a.swift', 'Text("x").bold()', 1],
    ['a.swift', 'Text("x").fontWeight(.semibold)', 1],
    ['a.swift', 'Text("x").font(MekaType.caption.weight(.semibold))', 1],
    ['a.swift', 'Image(systemName: "x").font(.system(size: 22))', 1],
    ['a.swift', 'Image(systemName: "x").font(.caption2)', 1],
    ['a.swift', '.font(.headline)', 1],
    ['a.swift', 'Text("x").font(MekaType.captionStrong)', 0],
    ['a.swift', '.font(free ? MekaType.body : MekaType.itemTitle)', 0],
    ['a.swift', 'Text("12").monospacedDigit()', 0],
    ['a.swift', '.font(.system(size: base * 0.34)) // type: ok (an icon size)', 0],
  ];
  const bad = cases.filter(([f, line, n]) => offences(f, line).length !== n);
  if (bad.length) { console.error('type-check self-test failed:\n' + bad.map((c) => JSON.stringify(c)).join('\n')); process.exit(1); }
  console.log(`type-check self-test: ${cases.length} cases pass.`);
  process.exit(0);
}

const files = [...walk(join(root, A)), ...walk(join(root, M)), ...walk(join(root, W))];
const found = files.flatMap((f) => offences(f, readFileSync(join(root, f), 'utf8')));
if (found.length) {
  console.error(`Type off the MekaType tokens (${found.length}):\n` + found.join('\n'));
  process.exit(1);
} else {
  console.log(`Type: all ${files.length} app source files use MekaType tokens only.`);
}
