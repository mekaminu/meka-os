@preconcurrency import MekaKit
import SwiftUI

/// Every page under Ask in one shape on the Mac (approved 2026-11-11, docs/design/look/LifePage.dc.html): the title with
/// an ⓘ that unfolds what the page does, one gold + Add, three number tiles, then cards of at most five rows. These
/// draw a `LifePageView` from the core; what a row or + Add's choice does is the page's own (each named by a key).
///
/// Motion: the ⓘ's words unfold on the expand spring; rows blend in; a chosen choice's colour blends. Reduce Motion:
/// cross-fades.
struct LifePageHeader: View {
    let title: String
    let info: String
    let palette: MekaPalette
    @Binding var infoOpen: Bool
    let onAdd: () -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(spacing: MekaSpace.xs) {
                Text(title).font(MekaType.upNextTitle).tracking(MekaType.upNextTitleTracking)
                    .foregroundStyle(palette.textPrimary)
                    .accessibilityAddTraits(.isHeader)
                Button {
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { infoOpen.toggle() }
                } label: {
                    Text("i").font(MekaType.caption).foregroundStyle(palette.accent)
                        .frame(width: MekaSpace.l, height: MekaSpace.l)
                        .overlay(Circle().stroke(palette.accent, lineWidth: 1))
                }
                .buttonStyle(MekaPressStyle())
                .accessibilityLabel(LifePageRules.shared.INFO_LABEL)
                .accessibilityValue(infoOpen ? "Shown" : "Hidden")
                Spacer()
                Button(action: onAdd) {
                    Text(LifePageRules.shared.ADD).font(MekaType.captionStrong).foregroundStyle(palette.onAccent)
                        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                        .background(Capsule().fill(palette.accent))
                }
                .buttonStyle(MekaPressStyle())
                .accessibilityLabel("Add")
            }
            if infoOpen {
                Text(info).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: MekaRadius.s).fill(palette.surfaceRaised))
                    .transition(.opacity)
            }
        }
    }
}

/// The three number tiles in a row: the number (brass when lit) over its words.
struct LifeNumberTiles: View {
    let page: LifePageView
    let palette: MekaPalette

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            ForEach(0..<Int(page.tileCount), id: \.self) { i in
                let t = page.tileAt(index: Int32(i))
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(t.number).font(MekaType.nowTitle).foregroundStyle(t.lit ? palette.accent : palette.textPrimary).lineLimit(1)
                    Text(t.label).font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(2)
                }
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: MekaRadius.s).fill(palette.surfaceRaised))
                .accessibilityElement(children: .combine)
            }
        }
    }
}

/// One card: its title and count, the gold link at the top right, then at most five rows (or the card's empty line).
struct LifePageCardView: View {
    let card: PageCard
    let palette: MekaPalette
    let onMore: () -> Void
    let onRow: (PageRow) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: MekaSpace.xs) {
                Text(card.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                Text("\(card.count)").font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .padding(.horizontal, MekaSpace.xs)
                    .background(Capsule().fill(palette.surface))
                Spacer()
                if let more = card.more {
                    Button("\(more) ›", action: onMore).buttonStyle(.link).font(MekaType.caption)
                }
            }
            .padding(.vertical, MekaSpace.xs)
            if card.rowCount == 0, let empty = card.empty {
                Text(empty).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.bottom, MekaSpace.xs)
            }
            ForEach(0..<Int(card.rowCount), id: \.self) { i in
                let r = card.rowAt(index: Int32(i))
                Divider().overlay(palette.hairline)
                Button { onRow(r) } label: {
                    HStack(spacing: MekaSpace.xs) {
                        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                            Text(r.name).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(1)
                            if !r.line.isEmpty {
                                Text(r.line).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1)
                            }
                        }
                        Spacer()
                        if !r.whenText.isEmpty {
                            Text(r.whenText).font(MekaType.caption).foregroundStyle(r.lit ? palette.accent : palette.textSecondary)
                        }
                    }
                    .padding(.vertical, MekaSpace.xs)
                    .contentShape(Rectangle())
                }
                .buttonStyle(MekaPressStyle())
                .accessibilityElement(children: .combine)
                .transition(.opacity)
            }
        }
        .padding(.horizontal, MekaSpace.m).padding(.bottom, MekaSpace.xxs)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
    }
}
