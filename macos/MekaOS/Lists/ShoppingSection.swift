@preconcurrency import MekaKit
import SwiftUI

/// SHOPPING on the Mac (family sharing, slice 1), like the Fold's: the shared shopping list, to buy in the order added,
/// then Got for a week. Clicking a row's ring ticks it (the ring sweeps, fills and draws its check, light haptic) and
/// the row glides under Got; clicking a got ring puts it back (tick haptic). Remove takes a row off for good; Clear
/// under Got clears what was bought. Reduce Motion: the check shown at once, cross-fades.
struct ShoppingSection: View {
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette

    var body: some View {
        let v = model.lists?.shopping
        let toBuy = v?.toBuy ?? []
        let got = v?.got ?? []
        Text(v?.line ?? "Nothing to buy")
            .font(MekaType.itemMeta)
            .foregroundStyle(palette.textSecondary)
            .contentTransition(.opacity)
            .padding(.bottom, MekaSpace.s)
        if toBuy.isEmpty && got.isEmpty {
            EmptyLine(text: ShoppingRules.shared.EMPTY_LINE, palette: palette)
        }
        ForEach(toBuy, id: \.id) { s in ShoppingRowView(item: s, palette: palette) }
        if !got.isEmpty {
            HStack {
                Text("GOT")
                    .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                    .foregroundStyle(palette.textTertiary)
                Spacer()
                Button("Clear") { model.clearGotShopping() }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.caption)
                    .foregroundStyle(palette.accent)
            }
            .padding(.top, MekaSpace.m)
            ForEach(got, id: \.id) { s in ShoppingRowView(item: s, palette: palette) }
        }
    }
}

private struct ShoppingRowView: View {
    @Environment(CoreModel.self) private var model
    let item: ShoppingItem
    let palette: MekaPalette

    var body: some View {
        HStack(spacing: MekaSpace.s) {
            Button {
                if item.got { model.putBackShopping(item.id) } else { model.gotShopping(item.id) }
            } label: {
                TickRingView(done: item.got, palette: palette)
                    .frame(width: 18, height: 18)
                    .contentShape(Circle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel((item.got ? "Put back " : "Got ") + item.title)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title)
                    .font(MekaType.body)
                    .foregroundStyle(item.got ? palette.textTertiary : palette.textPrimary)
                if let meta = item.meta {
                    Text(meta).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                }
            }
            Spacer()
            Button("Remove") { model.removeShopping(item.id) }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.textTertiary)
        }
        .padding(.horizontal, MekaSpace.s)
        .padding(.vertical, MekaSpace.xs)
        .transition(.opacity)
    }
}

/// Add to shopping: one line, several things separated by commas ("milk, eggs").
struct AddShoppingRow: View {
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette
    @State private var text = ""

    var body: some View {
        TextField(ShoppingRules.shared.ADD_HINT, text: $text)
            .textFieldStyle(.roundedBorder)
            .onSubmit { model.addShopping(text); text = "" }
    }
}
