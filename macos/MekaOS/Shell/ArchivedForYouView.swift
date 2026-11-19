import SwiftUI
@preconcurrency import MekaKit

/// "Archived for you" on the Mac (Inbox autopilot, slice 4), in Needs you under the group digest: from the 12:30 and
/// 18:30 digests, what Autopilot tidied since Meka last looked ("Tidied 14 · 9 newsletters · 3 receipts and orders · …"),
/// each tidy with its emails by sender (2026-10-16: "Deliveroo · 4 promos", each subject with a reason chip and its time) and Put back (the server returns those emails to the inbox at its next check). Got it folds it away here
/// and on the Fold (synced), with Undo on the bar. It staggers in after the cards above; Show/Hide unfolds the tidies on
/// the expand spring; the headline cross-fades. Reduced motion: cross-fades.
/// Slice 2: click an email → its preview unfolds under it (one at a time): who from, the subject, the provider's first
/// lines, and Put back (just that email), Always keep from this sender and Unsubscribe where it offered one, each done
/// at the server's next check with Undo on the bar until then.
struct ArchivedForYouView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    var firstIndex: Int = 2
    @State private var open = true
    /// Slice 2: the email whose preview is open ("batchId|key").
    @State private var openEmail: String?

    var body: some View {
        Group {
            if let card = model.archivedForYou {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Button { MekaHaptics.tick(); open.toggle() } label: {
                        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                            Text("\(card.title) · \(card.slot)".uppercased())
                                .font(MekaType.captionStrong).foregroundStyle(palette.textSecondary)
                            Text(card.headline).font(MekaType.body).foregroundStyle(palette.textPrimary)
                                .contentTransition(.opacity)
                                .animation(MekaMotion.expand(reduced: reduceMotion), value: card.headline)
                            // 2026-10-18: the same email again and again, one line each.
                            ForEach(card.repeats, id: \.self) { line in
                                Text(line).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(card.spoken)
                    if open {
                        VStack(alignment: .leading, spacing: MekaSpace.xs) {
                            ForEach(card.lines, id: \.id) { line in
                                ArchivedLineRow(line: line, palette: palette, openEmail: $openEmail) { model.putBackArchived(line.id) }
                            }
                            // Slice 3: "+ 4 earlier tidies · in All tidied mail" opens the whole list.
                            if let more = card.moreLine {
                                Button(more) { MekaHaptics.light(); model.showArchivedAll = true }
                                    .buttonStyle(.plain)
                                    .font(MekaType.itemMeta).foregroundStyle(palette.accent)
                            }
                            Text(card.note).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(.top, MekaSpace.xs)
                        .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    }
                    HStack(spacing: MekaSpace.xs) {
                        ArchivedPill(label: ArchivedForYouRules.shared.GOT_IT, palette: palette,
                                     spoken: "Got it, fold Archived for you away") { model.seenArchivedForYou() }
                        ArchivedPill(label: open ? ArchivedForYouRules.shared.HIDE : ArchivedForYouRules.shared.SHOW,
                                     palette: palette, quiet: true, spoken: open ? "Hide what was archived" : "Show what was archived") {
                            MekaHaptics.tick()
                            open.toggle()
                        }
                        ArchivedPill(label: ArchivedAllRules.shared.OPEN, palette: palette, quiet: true,
                                     spoken: "All tidied mail, every email tidied in the last 30 days") {
                            MekaHaptics.light()
                            model.showArchivedAll = true
                        }
                    }
                    .padding(.top, MekaSpace.m)
                }
                .padding(MekaSpace.m)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
                .mekaHoverLift()
                .staggeredAppear(firstIndex)
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.archivedForYou == nil)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: open)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: openEmail)
    }
}

private struct ArchivedLineRow: View {
    let line: ArchivedForYouLine
    let palette: MekaPalette
    @Binding var openEmail: String?
    let putBack: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(alignment: .center, spacing: MekaSpace.xs) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text("\(line.time) · \(line.account)").font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                    Text(line.detail).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    if let state = line.state {
                        Text(state).font(MekaType.itemMeta).foregroundStyle(palette.accent).transition(.opacity)
                    }
                }
                .accessibilityElement(children: .combine)
                Spacer(minLength: MekaSpace.xs)
                if line.canPutBack {
                    ArchivedPill(label: ArchivedForYouRules.shared.PUT_BACK, palette: palette,
                                 spoken: "Put back \(line.detail) in the inbox", action: putBack)
                }
            }
            // "Archived for you" shows which emails: the tidy's emails by sender, each subject with its reason and time
            // (the sender's words, shown as plain text only).
            if !line.emails.isEmpty {
                VStack(alignment: .leading, spacing: MekaSpace.m) {
                    ForEach(line.emails, id: \.id) { group in
                        ArchivedEmailGroupRow(group: group, line: line, palette: palette, openEmail: $openEmail)
                    }
                    if let more = line.emailsMore {
                        Text(more).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                    }
                }
                .padding(.leading, MekaSpace.m)
            }
        }
    }
}

/// One sender in a tidy: "Deliveroo · 4 promos", its domain and account, then each subject with a muted reason chip.
/// Slice 2: each email is a button that unfolds its preview.
private struct ArchivedEmailGroupRow: View {
    let group: ArchivedEmailGroup
    let line: ArchivedForYouLine
    let palette: MekaPalette
    @Binding var openEmail: String?

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(group.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Text(group.meta).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
            }
            .accessibilityElement(children: .combine)
            ForEach(group.rows, id: \.key) { row in
                let id = "\(row.batchId)|\(row.key)"
                Button {
                    MekaHaptics.tick()
                    openEmail = openEmail == id ? nil : id
                } label: {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                            Text(row.subject).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                                .lineLimit(2)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            Text(row.chip).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).lineLimit(1)
                                .padding(.horizontal, MekaSpace.xs).padding(.vertical, MekaSpace.xxs)
                                .background(palette.surface, in: Capsule())
                            Text(row.time).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                        }
                        if let state = row.state {
                            Text(state).font(MekaType.itemMeta).foregroundStyle(palette.accent).transition(.opacity)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel([row.subject, row.chip, row.time, row.state].compactMap { $0 }.joined(separator: ", ") + ". Opens its preview")
                .modifier(ArchivedSwipeMenu(row: row))
                if openEmail == id {
                    ArchivedEmailPreviewView(row: row, lineState: line.state, palette: palette)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }
            }
            if let more = group.moreLine {
                Text(more).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Slice 2: one email's preview, read from the synced tidy (read again as the row's state changes).
private struct ArchivedEmailPreviewView: View {
    @Environment(CoreModel.self) private var model
    let row: ArchivedEmailRow
    let lineState: String?
    let palette: MekaPalette
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var preview: ArchivedEmailPreview?
    @State private var openLine: String?
    @State private var vaultLine: String?
    /// Read the full email (2026-10-20): the reader is open.
    @State private var reading = false

    /// The reader's answers: the preview's own (Put back, Always keep, Unsubscribe).
    private func readerActions(_ p: ArchivedEmailPreview) -> [MailReaderAction] {
        let batchId = p.batchId, key = p.key
        var out: [MailReaderAction] = []
        // Tidied mail, slice 3: Not junk first on a junk or scam email.
        if let label = p.notJunk {
            out.append(MailReaderAction(id: "NOT_JUNK", label: label, spoken: "\(label), \(p.subject)",
                                        action: { model.askArchivedEmail(batchId: batchId, key: key, action: "NOT_JUNK") }))
        }
        if let label = p.putBack {
            out.append(MailReaderAction(id: "PUT_BACK", label: label, spoken: "\(label), \(p.subject)",
                                        action: { model.askArchivedEmail(batchId: batchId, key: key, action: "PUT_BACK") }))
        }
        if let label = p.keep {
            out.append(MailReaderAction(id: "KEEP", label: label, spoken: label,
                                        action: { model.askArchivedEmail(batchId: batchId, key: key, action: "KEEP") }))
        }
        if let label = p.unsubscribe {
            out.append(MailReaderAction(id: "UNSUBSCRIBE", label: label, spoken: label,
                                        action: { model.askArchivedEmail(batchId: batchId, key: key, action: "UNSUBSCRIBE") }))
        }
        // Tidied mail, slice 2: Move to Trash and Block sender.
        if let label = p.trash {
            out.append(MailReaderAction(id: "TRASH", label: label, spoken: "\(label), \(p.subject)",
                                        action: { model.askArchivedEmail(batchId: batchId, key: key, action: "TRASH") }))
        }
        if let label = p.block {
            out.append(MailReaderAction(id: "BLOCK", label: label, spoken: label,
                                        action: { model.askArchivedEmail(batchId: batchId, key: key, action: "BLOCK") }))
        }
        return out
    }

    var body: some View {
        Group {
            if let p = preview {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text("\(p.from) · \(p.meta)").font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                        Text(p.subject).font(MekaType.body).foregroundStyle(palette.textPrimary)
                            .fixedSize(horizontal: false, vertical: true)
                        Text("\(p.chip) · \(p.time)").font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                        Text(p.lines ?? MailTidiedAskRules.shared.NO_LINES).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                            .textSelection(.enabled)
                        if let state = p.state {
                            Text(state).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                        }
                    }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("\(p.spoken). \(MailReaderRules.shared.READ_WHOLE)")
                    .accessibilityAddTraits(.isButton)
                    .contentShape(Rectangle())
                    .onTapGesture { MekaHaptics.light(); reading = true }
                    .sheet(isPresented: $reading) {
                        let batchId = p.batchId, key = p.key
                        MailReaderSheet(palette: palette,
                                        load: { images in await model.readArchivedMail(batchId: batchId, key: key, images: images) },
                                        actions: readerActions(p),
                                        openLabel: p.openLabel,
                                        openInMail: { Task { openLine = await model.openArchivedEmail(batchId: batchId, key: key) } },
                                        attachment: { v, a in await model.openMailAttachment(v, a) })
                    }
                    HStack(spacing: MekaSpace.xs) {
                        ArchivedPill(label: MailReaderRules.shared.READ_WHOLE, palette: palette,
                                     spoken: "\(MailReaderRules.shared.READ_WHOLE), \(p.subject)") {
                            MekaHaptics.light(); reading = true
                        }
                        if let label = p.notJunk {
                            ArchivedPill(label: label, palette: palette, spoken: "\(label), \(p.subject)") {
                                model.askArchivedEmail(batchId: p.batchId, key: p.key, action: "NOT_JUNK")
                            }
                        }
                        if let label = p.putBack {
                            ArchivedPill(label: label, palette: palette, spoken: "\(label), \(p.subject)") {
                                model.askArchivedEmail(batchId: p.batchId, key: p.key, action: "PUT_BACK")
                            }
                        }
                        if let label = p.keep {
                            ArchivedPill(label: label, palette: palette, spoken: label) {
                                model.askArchivedEmail(batchId: p.batchId, key: p.key, action: "KEEP")
                            }
                        }
                        if let label = p.unsubscribe {
                            ArchivedPill(label: label, palette: palette, quiet: true, spoken: label) {
                                model.askArchivedEmail(batchId: p.batchId, key: p.key, action: "UNSUBSCRIBE")
                            }
                        }
                        // Slice 4: Open in Gmail / Outlook (the browser; the link is never kept).
                        if let label = p.openLabel {
                            let batchId = p.batchId, key = p.key
                            ArchivedPill(label: label, palette: palette, quiet: true, spoken: "\(label), \(p.subject)") {
                                Task { openLine = await model.openArchivedEmail(batchId: batchId, key: key) }
                            }
                        }
                    }
                    // Tidied mail, slice 2 (Meka 2026-11-18): Move to Trash and Block sender on their own line (the
                    // sheet is narrow), Undo on the bar until Navilife's next check.
                    if p.trash != nil || p.block != nil {
                        HStack(spacing: MekaSpace.xs) {
                            if let label = p.trash {
                                ArchivedPill(label: label, palette: palette, critical: true, spoken: "\(label), \(p.subject)") {
                                    model.askArchivedEmail(batchId: p.batchId, key: p.key, action: "TRASH")
                                }
                            }
                            if let label = p.block {
                                ArchivedPill(label: label, palette: palette, critical: true, spoken: label) {
                                    model.askArchivedEmail(batchId: p.batchId, key: p.key, action: "BLOCK")
                                }
                            }
                        }
                    }
                    if let hint = openLine ?? p.openHint {
                        Text(hint).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                            .contentTransition(.opacity)
                            .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: hint)
                    }
                    // Receipts → Vault: Keep in the Vault / Take it out of the Vault; the line cross-fades to what it did.
                    if let offer = p.vault {
                        let batchId = p.batchId, key = p.key, kept = offer.kept
                        ArchivedPill(label: offer.action, palette: palette, quiet: kept, spoken: "\(offer.action), \(p.subject)") {
                            Task {
                                vaultLine = await model.receiptInVault(batchId: batchId, key: key, keep: !kept)
                                preview = await model.archivedEmail(batchId: batchId, key: key)
                            }
                        }
                        let line = vaultLine ?? offer.line
                        Text(line).font(MekaType.itemMeta)
                            .foregroundStyle(kept || vaultLine != nil ? palette.accent : palette.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                            .contentTransition(.opacity)
                            .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: line)
                    }
                    Text(p.note).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(MekaSpace.m)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.s))
                .padding(.top, MekaSpace.xs)
            }
        }
        .task(id: "\(row.key)|\(row.state ?? "")|\(lineState ?? "")") {
            preview = await model.archivedEmail(batchId: row.batchId, key: row.key)
        }
    }
}

/// Tidied mail, slice 3 (Meka 2026-11-18: "swipe right = put back, swipe left = Trash"): the Mac's same moves sit in
/// the row's context menu and VoiceOver's actions (no swipe on a trackpad list); each asks with Undo on the bar.
private struct ArchivedSwipeMenu: ViewModifier {
    @Environment(CoreModel.self) private var model
    let row: ArchivedEmailRow

    func body(content: Content) -> some View {
        if let sw = row.swipe {
            let batchId = row.batchId, key = row.key
            let right = sw.right?.name, left = sw.left?.name
            let rightLabel = sw.rightLabel ?? "", trash = MailTidiedAskRules.shared.TRASH
            content
                .contextMenu {
                    if let a = right { Button(rightLabel) { model.askArchivedEmail(batchId: batchId, key: key, action: a) } }
                    if let a = left { Button(trash) { model.askArchivedEmail(batchId: batchId, key: key, action: a) } }
                }
                .accessibilityActions {
                    if let a = right { Button(rightLabel) { model.askArchivedEmail(batchId: batchId, key: key, action: a) } }
                    if let a = left { Button(trash) { model.askArchivedEmail(batchId: batchId, key: key, action: a) } }
                }
        } else {
            content
        }
    }
}

private struct ArchivedPill: View {
    let label: String
    let palette: MekaPalette
    var quiet: Bool = false
    /// Slice 2: Move to Trash and Block wear the critical colour.
    var critical: Bool = false
    let spoken: String
    let action: () -> Void

    var body: some View {
        Button(label, action: action)
            .buttonStyle(MekaPressStyle())
            .font(MekaType.itemMeta)
            .foregroundStyle(critical ? palette.critical : (quiet ? palette.textSecondary : palette.accent))
            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
            .background(palette.surface, in: Capsule())
            .accessibilityLabel(spoken)
    }
}

/// "Archived for you" shows which emails, slice 3: **All tidied mail** — every email Navilife tidied in the last 30 days,
/// newest first under its day, with the senders as filter chips ("Deliveroo · 12"; All first). Click an email: its
/// preview unfolds under it as on the card (Put back, Always keep, Unsubscribe, Undo on the bar inside the sheet).
/// Motion: the sheet scale-fades (system); the summary cross-fades; a chosen chip blends to the accent with a tick
/// haptic; days and rows stagger in and move on the expand spring as the filter changes; a preview unfolds on the
/// expand spring. Reduce Motion: cross-fades. Read again every 20 s while open (the server's answers come by sync).
struct ArchivedAllSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var sender: String?
    /// Meka 2026-11-18 23:34: a Needs you tile opens the sheet on today's emails of its kind; the chips change it.
    @State private var kind: String?
    @State private var kindRead = false
    @State private var list: ArchivedAllList?
    @State private var openEmail: String?
    @State private var reads = 0
    /// Tidied mail, slice 4: picking several, and the Trash all confirm.
    @State private var selecting = false
    @State private var selected: Set<String> = []
    @State private var confirming = false

    private var shown: [ArchivedAllRow] { (list?.days ?? []).flatMap { $0.rows } }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            Text(ArchivedAllRules.shared.TITLE).font(MekaType.upNextTitle).staggeredAppear(0)
            Text(list?.summary ?? " ")
                .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                .contentTransition(.opacity)
                .accessibilityLabel(list?.spoken ?? "")
                .staggeredAppear(0)
            if let l = list, !l.kinds.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: MekaSpace.xs) {
                        ForEach(l.kinds, id: \.label) { k in
                            chip(k.label, spoken: k.spoken, selected: k.selected) { kind = k.kind; sender = nil }
                        }
                    }
                }
                .staggeredAppear(1)
            }
            if let l = list, !l.senders.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: MekaSpace.xs) {
                        chip(ArchivedAllRules.shared.ALL, spoken: "Show every sender", selected: l.allSelected) { sender = nil }
                        ForEach(l.senders, id: \.id) { s in
                            chip(s.label, spoken: s.spoken, selected: s.selected) { sender = s.selected ? nil : s.id }
                        }
                    }
                }
                .staggeredAppear(1)
            }
            if let l = list { bulkControls(l).staggeredAppear(1) }
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    if let l = list {
                        ForEach(Array(l.days.enumerated()), id: \.element.id) { i, d in
                            Text(d.label.uppercased()).font(MekaType.captionStrong).foregroundStyle(palette.textSecondary)
                                .padding(.top, MekaSpace.m)
                                .staggeredAppear(2 + i)
                            ForEach(d.rows, id: \.id) { r in
                                row(r)
                                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                            }
                        }
                        ForEach([l.more, l.countsOnly, l.days.isEmpty && l.countsOnly == nil ? l.empty : nil].compactMap { $0 }, id: \.self) { line in
                            Text(line).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.m)
                        }
                    }
                    Text(ArchivedAllRules.shared.NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, MekaSpace.l)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxHeight: 520)
            if model.eventUndo != nil { EventUndoBar(palette: palette) }
            HStack {
                Spacer()
                Button(ArchivedAllRules.shared.CLOSE) { dismiss() }
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.cancelAction)
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 600)
        .background(palette.surface)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: openEmail)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: sender)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: kind)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: selecting)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: confirming)
        .onChange(of: (sender ?? "") + "|" + (kind ?? "")) { _, _ in selected = []; confirming = false }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: list?.summary)
        .onDisappear { model.archivedAllKind = nil }
        .task(id: (sender ?? "") + "|" + (kind ?? "")) {
            if !kindRead { kindRead = true; kind = model.archivedAllKind; if kind != nil { return } }
            // Read now, then again every 20 s while the sheet is open: asks and the server's answers arrive by sync.
            while !Task.isCancelled {
                list = await model.archivedAll(sender: sender, kind: kind)
                reads += 1
                try? await Task.sleep(for: .seconds(20))
            }
        }
        .onChange(of: model.eventUndo != nil) { _, _ in
            // An ask (or its Undo) changes the rows: read again straight away.
            Task { list = await model.archivedAll(sender: sender, kind: kind); reads += 1 }
        }
    }

    private func chip(_ label: String, spoken: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button {
            MekaHaptics.tick()
            openEmail = nil
            action()
        } label: {
            Text(label).font(MekaType.itemMeta).lineLimit(1)
                .foregroundStyle(selected ? palette.accent : palette.textSecondary)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(selected ? palette.surfaceRaised : palette.background, in: Capsule())
                .animation(MekaMotion.expand(reduced: reduceMotion), value: selected)
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(spoken)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// Slice 4: Select / Done and "Trash all 38 promos"; while selecting, the bar ("3 selected · Select all · Trash 3 ·
    /// Put back 2"); Trash all unfolds its confirm (how many, Trash for 30 days, never deleted for good).
    @ViewBuilder
    private func bulkControls(_ l: ArchivedAllList) -> some View {
        let rows = shown
        let canPick = !MailTidiedBulkRules.shared.selectable(rows: rows).isEmpty
        if canPick || l.trashAll != nil {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                HStack(spacing: MekaSpace.xs) {
                    if canPick {
                        ArchivedPill(label: selecting ? MailTidiedBulkRules.shared.DONE : MailTidiedBulkRules.shared.SELECT,
                                     palette: palette, quiet: !selecting,
                                     spoken: selecting ? "Stop selecting" : "Select several emails") {
                            MekaHaptics.tick()
                            selecting.toggle(); selected = []; confirming = false; openEmail = nil
                        }
                    }
                    if let all = l.trashAll, !selecting {
                        ArchivedPill(label: all.label, palette: palette, critical: true, spoken: all.spoken) {
                            MekaHaptics.tick(); confirming.toggle()
                        }
                    }
                }
                if selecting {
                    let bar = MailTidiedBulkRules.shared.bar(rows: rows, selected: selected)
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        Text(bar.line).font(MekaType.body).foregroundStyle(palette.textPrimary)
                            .accessibilityLabel(bar.spoken)
                        HStack(spacing: MekaSpace.xs) {
                            ArchivedPill(label: bar.toggle, palette: palette, quiet: true, spoken: bar.toggle) {
                                MekaHaptics.tick()
                                selected = MailTidiedBulkRules.shared.toggled(rows: rows, selected: selected)
                            }
                            if let t = bar.trash {
                                ArchivedPill(label: t, palette: palette, critical: true, spoken: "\(t), to Trash") {
                                    let ids = rows.filter { selected.contains($0.id) && $0.row.swipe?.left?.name == "TRASH" }.map(\.id)
                                    bulk(ids, "TRASH")
                                }
                            }
                            if let b = bar.putBack {
                                ArchivedPill(label: b, palette: palette, spoken: b) {
                                    let ids = rows.filter { selected.contains($0.id) && $0.row.swipe?.right?.name == "PUT_BACK" }.map(\.id)
                                    bulk(ids, "PUT_BACK")
                                }
                            }
                        }
                    }
                    .padding(MekaSpace.m)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
                    .transition(.opacity.combined(with: .move(edge: .top)))
                }
                if confirming, !selecting, let all = l.trashAll {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        Text(all.confirmTitle).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                        Text(all.confirmBody).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                        HStack(spacing: MekaSpace.xs) {
                            ArchivedPill(label: all.confirmButton, palette: palette, critical: true, spoken: all.confirmButton) {
                                bulk(all.ids, "TRASH")
                            }
                            ArchivedPill(label: MailTidiedBulkRules.shared.CANCEL, palette: palette, quiet: true,
                                         spoken: MailTidiedBulkRules.shared.CANCEL) {
                                MekaHaptics.tick(); confirming = false
                            }
                        }
                    }
                    .padding(MekaSpace.m)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
                    .transition(.opacity.combined(with: .move(edge: .top)))
                }
            }
        }
    }

    private func bulk(_ ids: [String], _ action: String) {
        model.askArchivedEmails(ids: ids, action: action, kind: kind)
        selected = []; selecting = false; confirming = false
    }

    @ViewBuilder
    private func row(_ r: ArchivedAllRow) -> some View {
        let id = r.id
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            let pickable = selecting && (r.row.swipe?.any ?? false)
            let picked = selected.contains(id)
            Button {
                MekaHaptics.tick()
                if pickable {
                    if picked { selected.remove(id) } else { selected.insert(id) }
                } else if !selecting {
                    openEmail = openEmail == id ? nil : id
                }
            } label: {
                HStack(alignment: .center, spacing: MekaSpace.xs) {
                if pickable {
                    Circle().fill(picked ? palette.accent : palette.background)
                        .overlay(Circle().stroke(picked ? palette.accent : palette.textTertiary, lineWidth: 2))
                        .frame(width: 18, height: 18)
                        .animation(MekaMotion.expand(reduced: reduceMotion), value: picked)
                }
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text("\(r.from) · \(r.meta)").font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                    HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                        Text(r.row.subject).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                            .lineLimit(2)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Text(r.row.chip).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).lineLimit(1)
                            .padding(.horizontal, MekaSpace.xs).padding(.vertical, MekaSpace.xxs)
                            .background(palette.background, in: Capsule())
                        Text(r.row.time).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                    }
                    if let state = r.row.state {
                        Text(state).font(MekaType.itemMeta).foregroundStyle(palette.accent).transition(.opacity)
                    }
                }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(pickable ? (picked ? "Selected. " : "Not selected. ") + r.spoken : r.spoken)
            .accessibilityAddTraits(pickable && picked ? .isSelected : [])
            .modifier(ArchivedSwipeMenu(row: r.row))
            if openEmail == id && !selecting {
                ArchivedEmailPreviewView(row: r.row, lineState: "\(reads)", palette: palette)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding(.vertical, MekaSpace.xxs)
    }
}
