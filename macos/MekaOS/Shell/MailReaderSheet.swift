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
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var view: MailReaderView?
    @State private var images = false
    @State private var link: MailLinkCheck?
    @State private var linkLine: String?

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
                        Button { MekaHaptics.tick(); linkLine = MailReaderRules.shared.ATTACHMENT_OPEN } label: {
                            VStack(alignment: .leading, spacing: 0) {
                                Text(a.name).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary).lineLimit(1)
                                Text(a.meta).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                            }
                            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                            .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.s))
                        }
                        .buttonStyle(MekaPressStyle())
                        .accessibilityLabel("\(a.name), \(a.meta)")
                    }
                }
            }
        }
        HStack(spacing: MekaSpace.xs) {
            ForEach(actions) { a in
                MailNeedsPill(label: a.label, palette: palette, critical: a.critical, spoken: a.spoken,
                              action: { MekaHaptics.medium(); dismiss(); a.action() })
            }
            if let openLabel, let openInMail {
                MailNeedsPill(label: openLabel, palette: palette, quiet: true, spoken: "\(openLabel), \(v.subject)",
                              action: { MekaHaptics.light(); openInMail() })
            }
        }
        Text(v.note).font(MekaType.caption).foregroundStyle(palette.textTertiary)
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
