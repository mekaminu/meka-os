#!/usr/bin/env node
// Spacing rhythm check (ADR-012 addendum 2026-10-10, Calm Today): screens on the rhythm space things only with
// MekaSpace tokens on the 8 / 16 / 24 rhythm (xxs 4 for a text's own second line, xs 8, m 16, l 24, xl 32,
// gutter 16, gutterWide 24, touch 48). The off-rhythm `s` (12) and literal numbers in padding, spacedBy, Spacer
// and SwiftUI spacing are refused. Dependency-free; CI runs it with the token check.
// Usage: node design/rhythm-check.mjs            (lists offences in the files on the rhythm; exit 1 if any)
//        node design/rhythm-check.mjs --self-test (the rules against known lines)
//        node design/rhythm-check.mjs --all      (the same over every app source file, for planning; never fails)
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const A = 'android/app/src/main/kotlin/os/meka/android/';
const M = 'macos/MekaOS/';

/** Files already on the rhythm. Each Calm Today spacing slice adds the screens it moved. */
export const ON_RHYTHM = [
  // Slice 4a: Today (Fold and Mac) and what sits on it.
  A + 'today/TodayRoute.kt',
  A + 'today/TimelineViews.kt',
  A + 'today/QuickAlarmRows.kt',
  A + 'today/HabitChipsRow.kt',
  A + 'today/ConnectCard.kt',
  A + 'today/MotionCardView.kt',
  A + 'today/SignInLine.kt',
  A + 'today/BatteryCare.kt',
  A + 'today/DayRingHero.kt',
  A + 'today/CommandCentre.kt',
  A + 'fold/CoverNow.kt',
  M + 'TodayView.swift',
  M + 'Today/TimelineViews.swift',
  M + 'Today/QuickAlarmRows.swift',
  M + 'Today/HabitChipsRow.swift',
  M + 'Today/NowCard.swift',
  M + 'Today/NewsTicker.swift',
  M + 'Today/DayRingView.swift',
  M + 'Today/CommandCentreViews.swift',
  M + 'Today/SignInLineView.swift',
  // Slice 4b: Needs you and the panes that open from Today (task detail, brief, shutdown, wake alarm, repeat, plan).
  A + 'today/NeedsYouRoute.kt',
  A + 'today/DecisionStack.kt',
  A + 'today/TriageCardView.kt',
  A + 'today/RequestCardView.kt',
  A + 'today/GroupDigestSection.kt',
  A + 'today/TaskDetailRows.kt',
  A + 'today/BriefPane.kt',
  A + 'today/ShutdownPane.kt',
  A + 'today/WakeSection.kt',
  A + 'today/RepeatSection.kt',
  A + 'today/PlanPane.kt',
  M + 'Shell/NeedsYouStackView.swift',
  M + 'Shell/RequestCardsView.swift',
  M + 'Shell/TriageCardsView.swift',
  M + 'Shell/GroupGistsView.swift',
  M + 'Shell/NeedsYouMeanwhileView.swift',
  M + 'TaskDetailRows.swift',
  M + 'Today/BriefSheet.swift',
  M + 'Today/ShutdownSheet.swift',
  M + 'Today/WakeSection.swift',
  M + 'RepeatViews.swift',
  // Slice 4b-2: Calendar (the tab, the event detail, Add/Edit event, the event undo bar).
  A + 'calendar/CalendarRoute.kt',
  A + 'calendar/EventDetailPane.kt',
  A + 'calendar/AddEventPane.kt',
  A + 'calendar/EventActionsUi.kt',
  M + 'Calendar/CalendarScreen.swift',
  M + 'Calendar/EventDetailSheet.swift',
  M + 'Calendar/AddEventSheet.swift',
];

const NUM = String.raw`\b(?!0(?:\.0+)?\b)\d+(?:\.\d+)?`;
const KOTLIN = [
  [/MekaSpace\.s\b/g, 'MekaSpace.s (12) is off the rhythm: xs 8, m 16 or l 24'],
  [new RegExp(String.raw`(?:padding|spacedBy|PaddingValues)\([^()]*?${NUM}\.dp`, 'g'), 'a literal dp in padding/spacedBy: use a MekaSpace token'],
  [new RegExp(String.raw`Spacer\(\s*Modifier\.(?:height|width)\(${NUM}\.dp`, 'g'), 'a literal dp Spacer: use a MekaSpace token'],
];
const SWIFT = [
  [/MekaSpace\.s\b/g, 'MekaSpace.s (12) is off the rhythm: xs 8, m 16 or l 24'],
  [new RegExp(String.raw`\.padding\((?:\.[a-zA-Z]+(?:, ?)?|\[[^\]]*\], ?)?${NUM}\)`, 'g'), 'a literal number in padding: use a MekaSpace token'],
  [new RegExp(String.raw`\bspacing: ?${NUM}\b(?!\.)`, 'g'), 'a literal spacing: use a MekaSpace token'],
];

export function offences(path, text) {
  const rules = path.endsWith('.kt') ? KOTLIN : path.endsWith('.swift') ? SWIFT : [];
  const out = [];
  const lines = text.split('\n');
  lines.forEach((line, i) => {
    if (/rhythm: ok/.test(line)) return; // a deliberate exception, said so on the line
    for (const [re, why] of rules) {
      re.lastIndex = 0;
      if (re.test(line)) out.push(`${path}:${i + 1}: ${why}\n    ${line.trim()}`);
    }
  });
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
    ['a.kt', 'Modifier.padding(top = MekaSpace.s)', 1],
    ['a.kt', 'Modifier.padding(horizontal = MekaSpace.xs, vertical = 2.dp)', 1],
    ['a.kt', 'Arrangement.spacedBy(6.dp)', 1],
    ['a.kt', 'Spacer(Modifier.height(10.dp))', 1],
    ['a.kt', 'Modifier.padding(top = MekaSpace.m).size(6.dp)', 0],
    ['a.kt', 'Modifier.padding(bottom = if (x) MekaSpace.xs else 0.dp)', 0],
    ['a.kt', 'Modifier.padding(top = 7.dp) // rhythm: ok (aligns the dot)', 0],
    ['a.kt', 'MekaSpace.sheet', 0],
    ['a.swift', '.padding(.top, MekaSpace.s)', 1],
    ['a.swift', '.padding(.vertical, 2)', 1],
    ['a.swift', '.padding(6)', 1],
    ['a.swift', '.padding([.top, .bottom], 3)', 1],
    ['a.swift', 'VStack(alignment: .leading, spacing: 2) {', 1],
    ['a.swift', 'HStack(spacing: 0) {', 0],
    ['a.swift', 'VStack(spacing: MekaSpace.xxs) {', 0],
    ['a.swift', '.padding(.horizontal, MekaSpace.gutterWide)', 0],
    ['a.swift', '.frame(width: 2, height: 12)', 0],
  ];
  const bad = cases.filter(([f, line, n]) => offences(f, line).length !== n);
  if (bad.length) { console.error('rhythm-check self-test failed:\n' + bad.map((c) => JSON.stringify(c)).join('\n')); process.exit(1); }
  console.log(`rhythm-check self-test: ${cases.length} cases pass.`);
  process.exit(0);
}

const all = process.argv.includes('--all');
const files = all ? [...walk(join(root, A)), ...walk(join(root, M))] : ON_RHYTHM;
const found = files.flatMap((f) => offences(f, readFileSync(join(root, f), 'utf8')));
if (all) {
  const per = {};
  for (const o of found) { const f = o.split(':')[0]; per[f] = (per[f] || 0) + 1; }
  Object.entries(per).sort((a, b) => b[1] - a[1]).forEach(([f, n]) => console.log(`${n}\t${f}`));
  console.log(`${found.length} offences in ${Object.keys(per).length} files`);
} else if (found.length) {
  console.error(`Spacing off the 8/16/24 rhythm (${found.length}):\n` + found.join('\n'));
  process.exit(1);
} else {
  console.log(`Spacing rhythm: ${files.length} files on 8/16/24.`);
}
