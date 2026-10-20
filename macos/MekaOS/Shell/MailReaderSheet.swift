import SwiftUI
import WebKit
@preconcurrency import MekaKit

/// One answer at the reader's foot: the same ones the email's card offers.
struct MailReaderAction: Identifiable {
    let id: String
    let label: String
    let spoken: String
    var critical: Bool = false
    let action: () -> Void
}

/// Read the full email, slice 2: what clicking an attachment gave, as plain Swift values (the core's answer is read on
/// the main actor in CoreModel): a picture or PDF for the in-app viewer, a file for another app, or a line why not.
enum MailFileOpened {
    case view(ViewedCopy)
    case system(name: String, fileName: String, data: Data)
    case line(String)
}

/// An attachment he chose to open in another app: written to its own folder under the temporary folder (older ones,
/// past an hour, swept each time) and opened with the Mac's default app for it. Returns the line to show, or nil.
@MainActor
enum MailFileHandOver {
    static func open(fileName: String, data: Data) -> String? {
        let fm = FileManager.default
        let dir = fm.temporaryDirectory.appendingPathComponent("navilife-mail-files", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let old = Date().addingTimeInterval(-3600)
        for f in (try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.contentModificationDateKey])) ?? [] {
            let at = (try? f.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            if at < old { try? fm.removeItem(at: f) }
        }
        let one = dir.appendingPathComponent(String(UUID().uuidString.prefix(12)), isDirectory: true)
        do {
            try fm.createDirectory(at: one, withIntermediateDirectories: true)
            let url = one.appendingPathComponent(fileName, isDirectory: false)
            try data.write(to: url, options: .atomic)
            guard NSWorkspace.shared.urlForApplication(toOpen: url) != nil else { return MailAttachmentRules.shared.NO_APP_LINE }
            return NSWorkspace.shared.open(url) ? nil : MailAttachmentRules.shared.FAILED_LINE
        } catch {
            return MailAttachmentRules.shared.FAILED_LINE
        }
    }
}

/// Read the full email inside Navilife (2026-10-20), slice 1, on the Mac: a sheet (the system sheet's scale-fade; Reduce
/// Motion cross-fades) with who it's from and the address, To and Cc, when and the account, the subject, the whole email,
/// its attachments by name and size, and the card's own answers at the foot. The email is untrusted (ADR-006): it's drawn
/// in a WKWebView with JavaScript off, nothing loaded until Show images (the page's CSP allows pictures only, even then),
/// and a clicked link never opens by itself: a panel says where it really goes, and only Open hands it to the browser.
/// A plain-text email (or one that couldn't be read) is selectable text. Reading it doesn't mark it read. Only Strings
/// and a Bool cross to the core.
struct MailReaderSheet: View {
    let palette: MekaPalette
    let load: @MainActor (Bool) async -> MailReaderView?
    let actions: [MailReaderAction]
    var openLabel: String? = nil
    var openInMail: (() -> Void)? = nil
    /// Slice 2: open one of the attachments (nil: listed only).
    var attachment: (@MainActor (MailReaderView, MailAttachmentRow) async -> MailFileOpened)? = nil
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var view: MailReaderView?
    @State private var images = false
    @State private var link: MailLinkCheck?
    @State private var linkLine: String?
    @State private var fetching: Int32?
    @State private var pending: MailAttachmentRow?
    @State private var confirming: MailAttachmentRow?
    @State private var viewing: ViewedCopy?

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(alignment: .firstTextBaseline) {
                Text(view.map { "EMAIL · \($0.account.uppercased())" } ?? "EMAIL")
                    .font(MekaType.captionStrong).foregroundStyle(palette.textSecondary)
                Spacer()
                Button(MailReaderRules.shared.CLOSE) { dismiss() }
                    .keyboardShortcut(.cancelAction)
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.metaStrong)
                    .foregroundStyle(palette.onAccent)
                    .padding(.horizontal, MekaSpace.l)
                    .padding(.vertical, MekaSpace.xs)
                    .background(Capsule().fill(palette.accent))
            }
            if let v = view {
                content(v)
            } else {
                Text(MailReaderRules.shared.LOADING).font(MekaType.body).foregroundStyle(palette.textTertiary)
                Spacer(minLength: 0)
            }
        }
        .padding(MekaSpace.l)
        .frame(minWidth: 620, idealWidth: 760, minHeight: 560, idealHeight: 720)
        .background(palette.background)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: link?.url)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: view?.canShowImages)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: confirming?.index)
        .task(id: fetching) { await runFetch() }
        .sheet(item: $viewing) { copy in
            VaultCopyViewer(copy: copy, palette: palette) { viewing = nil }
        }
        .task(id: images) {
            let shown = images
            if let v = await load(shown) { view = v }
        }
    }

    @ViewBuilder
    private func content(_ v: MailReaderView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(v.subject).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary).lineLimit(3).textSelection(.enabled)
            Text(v.from).font(MekaType.metaStrong).foregroundStyle(palette.textPrimary)
            if let address = v.fromAddress {
                Text(address).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).textSelection(.enabled)
            }
            if let to = v.toLine { Text(to).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary).lineLimit(2) }
            if let cc = v.ccLine { Text(cc).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary).lineLimit(2) }
            Text("\(v.whenLine) · \(v.account)").font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(v.spoken)
        if let problem = v.problem {
            Text(problem).font(MekaType.itemMeta).foregroundStyle(palette.critical)
        }
        if v.canShowImages {
            HStack(spacing: MekaSpace.xs) {
                Text(v.imagesLine ?? "").font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                MailNeedsPill(label: MailReaderRules.shared.SHOW_IMAGES, palette: palette, spoken: "Show this email's pictures",
                              action: { MekaHaptics.light(); images = true })
            }
            .transition(.opacity)
        }
        Group {
            if let page = v.page {
                MailWebView(page: page) { url in
                    MekaHaptics.tick()
                    let c = MailReaderLinks.shared.check(url: url)
                    link = c
                    linkLine = c == nil ? MailReaderLinks.shared.NOT_OPENED : nil
                }
            } else {
                ScrollView {
                    Text(v.text).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(MekaSpace.m)
                }
                .scrollIndicators(.never)
                .background(palette.surfaceRaised)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipShape(RoundedRectangle(cornerRadius: MekaRadius.m))
        if let linkLine { Text(linkLine).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary) }
        if let l = link {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                Text(l.title).font(MekaType.metaStrong).foregroundStyle(palette.textPrimary)
                Text(l.shown).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).lineLimit(4).textSelection(.enabled)
                HStack(spacing: MekaSpace.xs) {
                    MailNeedsPill(label: l.action, palette: palette, spoken: "\(l.action): \(l.shown)", action: {
                        MekaHaptics.light()
                        if let u = URL(string: l.url) { NSWorkspace.shared.open(u) }
                        link = nil
                    })
                    MailNeedsPill(label: MailReaderLinks.shared.CANCEL, palette: palette, quiet: true, spoken: "Don't open the link",
                                  action: { MekaHaptics.tick(); link = nil })
                }
            }
            .padding(MekaSpace.m)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
            .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .bottom)))
        }
        if !v.attachments.isEmpty {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text((v.attachmentsLine ?? "").uppercased()).font(MekaType.captionStrong).foregroundStyle(palette.textSecondary)
                HStack(spacing: MekaSpace.xs) {
                    ForEach(Array(v.attachments.enumerated()), id: \.offset) { _, a in
                        Button { tap(v, a) } label: {
                            VStack(alignment: .leading, spacing: 0) {
                                Text(a.name).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary).lineLimit(1)
                                Text(a.meta).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                            }
                            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                            .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.s))
                            .opacity(fetching == a.index ? 0.55 : 1)
                        }
                        .buttonStyle(MekaPressStyle())
                        .accessibilityLabel("Open \(a.name), \(a.meta)")
                    }
                }
            }
        }
        // Slice 2: a file for another app waits for his yes, naming it and who sent it.
        if let a = confirming {
            let c = MailAttachmentRules.shared.confirm(name: a.name, from: v.from)
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                Text(c.title).font(MekaType.metaStrong).foregroundStyle(palette.textPrimary)
                Text(c.body).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                HStack(spacing: MekaSpace.xs) {
                    MailNeedsPill(label: c.action, palette: palette, spoken: "Open \(a.name) in another app", action: {
                        MekaHaptics.light(); confirming = nil; fetch(v, a)
                    })
                    MailNeedsPill(label: c.cancel, palette: palette, quiet: true, spoken: "Don't open \(a.name)",
                                  action: { MekaHaptics.tick(); confirming = nil })
                }
            }
            .padding(MekaSpace.m)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
            .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .bottom)))
        }
        HStack(spacing: MekaSpace.xs) {
            ForEach(actions) { a in
                MailNeedsPill(label: a.label, palette: palette, critical: a.critical, spoken: a.spoken,
                              action: { MekaHaptics.medium(); dismiss(); a.action() })
            }
            if let openLabel, let openInMail {
                MailNeedsPill(label: openLabel, palette: palette, quiet: true, spoken: "\(openLabel), \(v.subject)",
                              action: { MekaHaptics.light(); openInMail() })
            } else if let l = v.openLink {
                // Slice 3: a From your mail card's email opens in Gmail/Outlook too; the hint says where to look.
                let label = l.label, hint = l.hint, url = l.url
                MailNeedsPill(label: label, palette: palette, quiet: true, spoken: "\(label), \(v.subject)", action: {
                    MekaHaptics.light()
                    link = nil
                    linkLine = hint
                    if let u = URL(string: url) { NSWorkspace.shared.open(u) }
                })
            }
        }
        Text(v.note).font(MekaType.caption).foregroundStyle(palette.textTertiary)
    }

    /// A click on an attachment: never opened when it could run something or is too big; a picture or PDF fetched for
    /// the viewer; anything else asked about first.
    private func tap(_ v: MailReaderView, _ a: MailAttachmentRow) {
        let rules = MailAttachmentRules.shared
        link = nil
        if let said = rules.planLine(plan: a.plan) {
            MekaHaptics.tick(); confirming = nil; linkLine = said
        } else if attachment == nil {
            MekaHaptics.tick(); linkLine = rules.FAILED_LINE
        } else if a.plan == rules.SYSTEM {
            MekaHaptics.tick(); linkLine = nil; confirming = a
        } else {
            MekaHaptics.light(); confirming = nil; fetch(v, a)
        }
    }

    /// Starts fetching [a]; the sheet's `.task(id: fetching)` does the work on the main actor (no Kotlin value is handed
    /// to another task), and only Strings and numbers go on to the core (CoreModel.openMailAttachment).
    private func fetch(_ v: MailReaderView, _ a: MailAttachmentRow) {
        guard attachment != nil, fetching == nil else { return }
        pending = a
        fetching = a.index
        linkLine = "Opening \(a.name)…"
    }

    private func runFetch() async {
        guard let a = pending, let v = view, let attachment else { return }
        pending = nil
        let got = await attachment(v, a)
        fetching = nil
        switch got {
        case .view(let copy):
            linkLine = nil
            viewing = copy
        case .system(_, let fileName, let data):
            linkLine = MailFileHandOver.open(fileName: fileName, data: data)
        case .line(let line):
            linkLine = line
        }
    }
}

/// The email's page: JavaScript off, nothing but the page's own pictures (CSP), and every navigation stopped and handed to
/// [onLink] so the sheet can say where it really goes.
private struct MailWebView: NSViewRepresentable {
    let page: String
    let onLink: (String) -> Void

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate {
        var onLink: (String) -> Void
        var loaded: String?
        init(onLink: @escaping (String) -> Void) { self.onLink = onLink }

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction) async -> WKNavigationActionPolicy {
            // Only the page Navilife loaded itself may show; anything he clicks is asked about first, never opened here.
            let url = navigationAction.request.url?.absoluteString ?? ""
            if navigationAction.navigationType == .other, url == "about:blank" || url.isEmpty { return .allow }
            if !url.isEmpty { onLink(url) }
            return .cancel
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(onLink: onLink) }

    func makeNSView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.defaultWebpagePreferences.allowsContentJavaScript = false
        config.preferences.javaScriptCanOpenWindowsAutomatically = false
        config.websiteDataStore = .nonPersistent()
        let web = WKWebView(frame: .zero, configuration: config)
        web.navigationDelegate = context.coordinator
        web.allowsLinkPreview = false
        return web
    }

    func updateNSView(_ web: WKWebView, context: Context) {
        context.coordinator.onLink = onLink
        if context.coordinator.loaded != page {
            context.coordinator.loaded = page
            web.loadHTMLString(page, baseURL: nil)
        }
    }
}
