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
   ("● House"): **missing on the unfolded weekday cards** (dot only); the cover screen shows it.
3. Day header: serif date + weekday, budget pill, the thin done bar: **matches**. The word "planned" in the pill:
   **missing on the unfolded weekday columns** (the compact pill shows the figures only); the weekend and the cover
   screen show it. Past days with nothing on them show only their date (no pill, no "+ add").
4. Events as cards with a blue time chip: **matches** (slice 1 tests).
5. "+ add" at each column's foot, dashed: **matches**.
6. Header: "October 2026 W43", Obi's pill beside Today (unfolded) or under the month (cover), ‹ ›: **matches**. The
   design's ‹ › are round outlined buttons; the app's are plain arrows.
7. Timeline on the right, 07–22, blocks by area, now-line: **matches** (unfolded it starts shut at 829 dp; see slice 5).
8. Someday / Side hustle / House (+ No day yet) as card columns under a hairline: **matches** (`inner-dark-lists` on the
   screens branch).
9. Folded: 7-day pill strip, one day at a time, same cards, header and budget pill, Cards · Timeline tabs: **matches**.
10. Type: serif for dates and the month, mono for times and figures: **matches** (the platform's serif and mono).

Calm with few tasks: no empty bordered boxes anywhere.
