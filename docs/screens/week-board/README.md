# Week board: the design beside the app

The approved Option B design (`docs/design/week-board/option-b.dc.html`) rendered at 1280 and 390 wide, beside the
Fold's screens from CI run 1206 (aafb26c; Robolectric with invented tasks, Wednesday 21 Oct). The unfolded Fold is
about 829 dp wide, so it sits between the two design widths. The Mac's shots come with the nightly macOS run.

| Design | App |
| --- | --- |
| `design-1280.png` | `app-fold-unfolded.png`, `app-fold-unfolded-nudge.png` (unfolded Fold) |
| `design-390.png` | `app-fold-cover.png` (cover screen) |

## Checked item by item (2026-10-24)

1. No folded days; Mon–Fri as five equal columns, past ones dimmed; weekend as two shorter columns underneath: **matches**.
2. Cards: time chip, duration on the right in mono, title, tick on the left: **matches**. The area's label beside its dot
   ("● House"): **matches** since match slice 4 (the narrow weekday lanes cut it short with "…" only if they must).
3. Day header: serif date + weekday, budget pill, the thin done bar: **matches**. The word "planned" in the pill:
   **matches** since match slice 4 (the narrow weekday lanes stack it over the figures). A past day with nothing on it
   says "Nothing planned" under its date (no pill, no "+ add").
4. Events as cards with a blue time chip: **matches** (slice 1 tests).
5. "+ add" at each column's foot, dashed: **matches**.
6. Header: "October 2026 W43", Obi's pill beside Today (unfolded) or under the month (cover), ‹ ›: **matches**. ‹ › and
   Today are the design's round outlined 40 buttons since match slice 4.
7. Timeline on the right, 07–22, blocks by area, now-line: **matches** (unfolded it starts shut at 829 dp; see slice 5).
8. Someday / Side hustle / House (+ No day yet) as card columns under a hairline: **matches** (`inner-dark-lists` on the
   screens branch).
9. Folded: 7-day pill strip, one day at a time, same cards, header and budget pill, Cards · Timeline tabs: **matches**.
10. Type: serif for dates and the month, mono for times and figures: **matches** (the platform's serif and mono).

Match slice 4 adds `inner-dark-match-gaps` (screens branch).

Calm with few tasks: no empty bordered boxes anywhere.

## The density pass (2026-10-24, CI run 1211)

`app-fold-unfolded.png` is now `inner-dark-dense` (screens branch) at the Fold's real 829 dp: Mon–Fri and today's
timeline fit side by side (each weekday ~116 dp, the timeline 200 dp), dates 19 sp serif, cards 13.5 sp with "● House"
beside the tick, no "Any time" chips (the length alone), the weekend no wider than 1.5 weekdays. Still off against
`design-1280.png`, for the next slice: the header is the old size (the 34 sp month, 48 dp Obi pill and buttons) and
the goals row takes a line of its own; "+ add" is a 40 dp box where the mockup's is a slim dashed row; the narrow
pills stack "nothing yet / 4h" over three lines; the weekend's empty pills and "+ add" are still big.
