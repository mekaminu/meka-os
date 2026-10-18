import AppKit
import Foundation
import ImageIO
import MekaKit
import PDFKit
import SwiftUI
import UniformTypeIdentifiers

/// The Vault's copies on the Mac (Vault slice 2b), like the Fold's VaultCopies: a picked photo turned upright and
/// re-encoded as a JPEG of at most `PHOTO_LONG_SIDE` on its long side (smaller until it fits), a PDF as it is, all off
/// the main actor and returned as plain Strings, so nothing non-Sendable crosses isolation. The core seals it with the
/// Vault key; nothing here keeps a file.
nonisolated enum MacVaultCopy {
    /// A picked file ready for the core, or why it can't be.
    nonisolated struct Picked: Sendable {
        let type: String?
        let base64: String?
        let problem: String?
    }

    static let qualities: [Double] = [0.85, 0.72, 0.60, 0.48]

    static func prepare(url: URL) async -> Picked {
        await Task.detached(priority: .userInitiated) { () -> Picked in
            let rules = VaultFileRules.shared
            let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType
            guard let type = rules.typeOf(mime: mime, name: url.lastPathComponent) else {
                return Picked(type: nil, base64: nil, problem: rules.NOT_TYPE)
            }
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map { Int64($0) } ?? 0
            if type == rules.PDF {
                if size > rules.MAX_FILE_BYTES { return Picked(type: type, base64: nil, problem: rules.TOO_BIG) }
                guard let data = try? Data(contentsOf: url), !data.isEmpty else {
                    return Picked(type: type, base64: nil, problem: rules.COULDNT_READ)
                }
                if Int64(data.count) > rules.MAX_FILE_BYTES { return Picked(type: type, base64: nil, problem: rules.TOO_BIG) }
                return Picked(type: type, base64: data.base64EncodedString(), problem: nil)
            }
            guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else {
                return Picked(type: type, base64: nil, problem: rules.COULDNT_READ)
            }
            guard let jpeg = photo(source, longSide: Int(rules.PHOTO_LONG_SIDE), max: rules.MAX_FILE_BYTES) else {
                return Picked(type: type, base64: nil, problem: rules.TOO_BIG)
            }
            return Picked(type: rules.JPEG, base64: jpeg.base64EncodedString(), problem: nil)
        }.value
    }

    /// The picture upright at most `longSide` on its long side, as the first JPEG quality under `max` bytes.
    private static func photo(_ source: CGImageSource, longSide: Int, max: Int64) -> Data? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: longSide,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        for q in qualities {
            let out = NSMutableData()
            guard let dest = CGImageDestinationCreateWithData(out as CFMutableData, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
            CGImageDestinationAddImage(dest, image, [kCGImageDestinationLossyCompressionQuality: q] as CFDictionary)
            guard CGImageDestinationFinalize(dest) else { return nil }
            if Int64(out.length) <= max { return out as Data }
        }
        return nil
    }
}

/// An opened copy for the viewer: plain Swift values only (the bytes stay in memory).
struct ViewedCopy: Identifiable, Equatable {
    let id = UUID()
    let title: String
    let isPdf: Bool
    let data: Data
}

/// An opened copy in a sheet, in the app only: the document's name, the photo or the PDF (PDFKit, scrolls), and
/// Close. Motion: the sheet's own presentation, the content fading in once drawn; Reduce Motion: the same (the system
/// sheet honours it).
struct VaultCopyViewer: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let copy: ViewedCopy
    let palette: MekaPalette
    let onClose: () -> Void
    @State private var shown = false

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack {
                Text(copy.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                Spacer()
                Button(VaultFileRules.shared.CLOSE, action: onClose)
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.accent)
                    .keyboardShortcut(.cancelAction)
                    .accessibilityLabel("Close \(copy.title)")
            }
            Group {
                if copy.isPdf {
                    PDFCopyView(data: copy.data)
                } else if let image = NSImage(data: copy.data) {
                    ScrollView {
                        Image(nsImage: image).resizable().scaledToFit()
                            .clipShape(RoundedRectangle(cornerRadius: MekaRadius.m))
                            .accessibilityLabel(copy.title)
                    }
                    .scrollIndicators(.never)
                } else {
                    Text(VaultFileRules.shared.CHANGED).font(MekaType.caption).foregroundStyle(palette.accent)
                }
            }
            .opacity(shown ? 1 : 0)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: shown)
        }
        .padding(MekaSpace.m)
        .frame(minWidth: 560, idealWidth: 720, minHeight: 480, idealHeight: 760)
        .background(palette.background)
        .onAppear { shown = true }
    }
}

/// PDFKit's view of an opened copy, from memory only.
private struct PDFCopyView: NSViewRepresentable {
    let data: Data

    func makeNSView(context: Context) -> PDFView {
        let view = PDFView()
        view.autoScales = true
        view.displayMode = .singlePageContinuous
        view.document = PDFDocument(data: data)
        return view
    }

    func updateNSView(_ view: PDFView, context: Context) {}
}
