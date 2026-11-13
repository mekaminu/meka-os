import AppKit
@preconcurrency import MekaKit
import SwiftUI
import UniformTypeIdentifiers

/// Ask → Life → Bins on the Mac in the page shape (every page under Ask in one shape, slice 5; approved 2026-11-11,
/// docs/design/look/LifePage.dc.html), like the Fold's BinsPane: the title with its ⓘ, one gold + Add (A bin · A
/// one-off, each a field: "Food caddy every Tuesday", "Black bin Tue fortnightly from 13 Oct", "No black bin 29 Dec";
/// The council's dates opens a panel for the council's bin calendar (.ics), read on this Mac, nothing sent), three
/// numbers (the next collection, the bins, the one-offs to come), then Your bins, One-offs and Reminder and dates. A
/// row opens in place with ‹ Bins back: a bin's pattern and Remove, a one-off's Remove, the reminder's time (17:00 to
/// 21:00, synced with the Fold), the council's Use the council's dates… / Use my own days. Only Strings and an Int32
/// cross to the core.
///
/// Motion: the sheet scale-fades (system); sections stagger in; + Add's choices and the ⓘ unfold on the expand spring;
/// a row opens and closes with a cross-fade and slide from the trailing edge; the added line cross-fades over the
/// summary (a refused line lit in the accent); Remove a tick. Reduce Motion: cross-fades.
struct BinsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var infoOpen = false
    @State private var adding = false
    @State private var addChoice: String?
    @State private var text = ""
    /// What the last Add said, while the sheet is open.
    @State private var said: CoreModel.BinSaid?
    /// The row that's open, if any (its key: "bin:…", "move:…", "set:remind", "set:council").
    @State private var open: String?
    @State private var showAll = false

    var body: some View {
        let rules = BinsPageRules.shared
        let v = model.bins
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            if let key = open, let v {
                Button("‹ \(rules.TITLE)") { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = nil } }
                    .buttonStyle(.link).font(MekaType.caption)
                rowDetail(key, v)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .trailing)))
            } else if showAll {
                Button("‹ \(rules.TITLE)") { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { showAll = false } }
                    .buttonStyle(.link).font(MekaType.caption)
                Text(rules.YOURS).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        ForEach(v?.rows ?? [], id: \.id) { r in
                            Button { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = rules.binKey(id: r.id); showAll = false } } label: {
                                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                                    Text(r.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                                    Text(r.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(MekaPressStyle())
                            .accessibilityElement(children: .combine)
                            .accessibilityLabel(r.spoken)
                        }
                    }
                }
                .frame(maxHeight: 400)
                .transition(.opacity)
            } else if let page = model.binsPage() {
                LifePageHeader(title: page.title, info: page.info, palette: palette, infoOpen: $infoOpen) {
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { adding.toggle(); addChoice = nil; said = nil }
                }
                .staggeredAppear(0)
                if adding { addForm(page) }
                Text(said?.line ?? v?.summary ?? BinRules.shared.EMPTY_LINE)
                    .font(MekaType.caption).foregroundStyle(said?.refused == true ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
                    .staggeredAppear(1)
                LifeNumberTiles(page: page, palette: palette).staggeredAppear(2)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.m) {
                        ForEach(0..<Int(page.cardCount), id: \.self) { i in
                            LifePageCardView(card: page.cardAt(index: Int32(i)), palette: palette, onMore: {
                                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { showAll = true }
                            }, onRow: { r in
                                let key = r.key
                                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { said = nil; open = key }
                            })
                            .staggeredAppear(3 + i)
                        }
                    }
                }
                .frame(maxHeight: 420)
            }
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 480)
        .background(palette.surface)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: open)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: showAll)
    }

    /// What an open row shows: a bin or a one-off with Remove, the reminder's time, or the council's dates.
    @ViewBuilder
    private func rowDetail(_ key: String, _ v: BinsView) -> some View {
        let rules = BinsPageRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if let bin = rules.bin(view: v, key: key) {
                Text(bin.name).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                Group {
                    Text(bin.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    if let note = bin.note { Text(note).font(MekaType.caption).foregroundStyle(palette.accent) }
                }
                .accessibilityElement(children: .combine)
                Text("To change its day, + Add it again with the same name.").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                removeButton("Remove \(bin.name)") { model.removeBin(bin.id) }
            } else if let move = rules.oneOff(view: v, key: key) {
                Text(move.name).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                Text(move.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                Text("Remove it and the collection goes on its usual day again.").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                removeButton("Remove the one-off for \(move.name), \(move.line)") { model.removeBinMove(move.id) }
            } else if key == rules.KEY_REMIND {
                let minute = Int(v.remindMinute)
                Text(rules.REMINDER).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                Picker("Reminder", selection: Binding(get: { minute }, set: { model.setBinReminder(minute: $0) })) {
                    ForEach(v.remindChoices.map { Int($0.int32Value) }, id: \.self) { m in
                        Text(LocalClock.companion.formatMinute(m: Int32(m))).tag(m)
                    }
                }
                .labelsHidden().frame(width: 90)
                .accessibilityLabel("Bins reminder time")
                Text(v.remindLine).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: v.remindLine)
            } else if key == rules.KEY_COUNCIL {
                Text(rules.COUNCIL).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                if let line = v.councilLine {
                    Text(line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                }
                HStack(spacing: MekaSpace.xs) {
                    Button(v.councilLine == nil ? "Use the council's dates…" : "Pick a newer calendar…") { chooseCalendar() }
                        .accessibilityLabel("Use the council's dates: pick the bin calendar file from your council")
                    if v.councilLine != nil {
                        Button("Use my own days") { Task { said = await model.useTypedBins() } }
                    }
                }
                Text(v.footLine).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                if let said {
                    Text(said.line).font(MekaType.caption)
                        .foregroundStyle(said.refused ? palette.accent : palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            } else {
                // Removed while it was open.
                Text("Gone from your bins").font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
    }

    private func removeButton(_ label: String, _ action: @escaping () -> Void) -> some View {
        Button("Remove") {
            MekaHaptics.tick()
            action()
            withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = nil }
        }
        .buttonStyle(MekaPressStyle())
        .font(MekaType.caption)
        .foregroundStyle(palette.textTertiary)
        .accessibilityLabel(label)
    }

    @ViewBuilder
    private func addForm(_ page: LifePageView) -> some View {
        let rules = BinsPageRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(page.addTitle).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
            HStack(spacing: MekaSpace.xs) {
                ForEach(0..<Int(page.addChoiceCount), id: \.self) { i in
                    let c = page.addChoiceAt(index: Int32(i))
                    let key = c.key
                    chip(c.label, lit: addChoice == key) {
                        said = nil
                        if rules.takesText(choice: key) { addChoice = key } else { adding = false; addChoice = nil; chooseCalendar() }
                    }
                    .help(c.hint)
                }
            }
            if let choice = addChoice {
                TextField(rules.hint(choice: choice), text: $text)
                    .textFieldStyle(.roundedBorder)
                    .accessibilityLabel(BinRules.shared.ADD_DESCRIPTION)
                    .onSubmit { submit() }
            }
        }
        .transition(.opacity)
    }

    private func submit() {
        let typed = text.trimmingCharacters(in: .whitespaces)
        guard !typed.isEmpty else { return }
        Task {
            guard let r = await model.addBin(typed) else { return }
            said = r
            if !r.refused { text = ""; adding = false; addChoice = nil }
        }
    }

    private func chip(_ label: String, lit: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label).font(MekaType.caption).lineLimit(1)
                .foregroundStyle(lit ? palette.onAccent : palette.textPrimary)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(Capsule().fill(lit ? palette.accent : palette.surfaceRaised))
                .animation(MekaMotion.appear(reduced: reduceMotion), value: lit)
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityAddTraits(lit ? .isSelected : [])
    }

    /// An open panel for the council's bin calendar (.ics); its text is read here and handed to the core as a String.
    private func chooseCalendar() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.calendarEvent, UTType(filenameExtension: "ics") ?? .calendarEvent, .plainText, .text, .data]
        panel.allowsMultipleSelection = false
        panel.message = "Pick the bin calendar (.ics) from your council's bin-day page · read on this Mac, nothing sent"
        panel.prompt = "Use it"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        Task {
            let text = (try? String(contentsOf: url, encoding: .utf8)) ?? (try? String(contentsOf: url, encoding: .isoLatin1))
            if let text {
                said = await model.useCouncilBins(text)
            } else {
                MekaHaptics.tick()
                said = CoreModel.BinSaid(line: BinCouncilRules.shared.TOO_BIG, refused: true)
            }
        }
    }
}
