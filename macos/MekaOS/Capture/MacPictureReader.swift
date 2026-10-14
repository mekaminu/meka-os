import AppKit
import Foundation
import ImageIO
import MekaKit
import UniformTypeIdentifiers
import Vision

/// Screenshots and photos that do something (slice 1), the Mac's half: a picture is read **on this Mac** with Vision's
/// text recognition (on-device; Needs Meka #23's yes). Only the words go on, into Handle this, exactly as shared text
/// does; the picture is never sent, uploaded or kept. Everything here runs off the main actor and returns plain
/// Strings, so nothing non-Sendable crosses isolation.
nonisolated enum MacPictureReader {
    /// What reading came to: the tidied words, or the line to show instead ("No words in that picture · …").
    nonisolated enum Outcome: Sendable {
        case words(String)
        case nothing(String)
    }

    /// Reads the picture file at `url` (a screenshot on the Desktop, a photo from Finder).
    static func read(url: URL) async -> Outcome {
        await Task.detached(priority: .userInitiated) { () -> Outcome in
            let rules = PictureTextRules.shared
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map { Int64($0) }
            if let size, rules.tooBig(bytes: KotlinLong(value: size)) { return .nothing(rules.TOO_BIG_LINE) }
            guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return .nothing(rules.COULD_NOT_OPEN_LINE) }
            return recognise(source)
        }.value
    }

    /// Reads a picture handed over as bytes (a copied screenshot, an image dropped from a browser).
    static func read(data: Data) async -> Outcome {
        await Task.detached(priority: .userInitiated) { () -> Outcome in
            let rules = PictureTextRules.shared
            if rules.tooBig(bytes: KotlinLong(value: Int64(data.count))) { return .nothing(rules.TOO_BIG_LINE) }
            guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return .nothing(rules.COULD_NOT_OPEN_LINE) }
            return recognise(source)
        }.value
    }

    /// Shrinks the picture upright to the read size, reads it with Vision (accurate, British English first) and tidies
    /// the lines with the core's rules.
    private static func recognise(_ source: CGImageSource) -> Outcome {
        let rules = PictureTextRules.shared
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: Int(rules.READ_MAX_PX),
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            return .nothing(rules.COULD_NOT_OPEN_LINE)
        }
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        request.recognitionLanguages = ["en-GB", "en-US"]
        do {
            try VNImageRequestHandler(cgImage: image, options: [:]).perform([request])
        } catch {
            return .nothing(rules.COULD_NOT_OPEN_LINE)
        }
        let raw = (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }.joined(separator: "\n")
        let text = rules.tidy(raw: raw)
        return rules.worthOffering(text: text) ? .words(text) : .nothing(rules.NOTHING_LINE)
    }

    /// The picture on a pasteboard (Services, the clipboard): a file URL to an image first, else image bytes. Read on
    /// the calling thread (cheap); the bytes or URL are Sendable.
    static func picture(on pboard: NSPasteboard) -> Picture? {
        let opts: [NSPasteboard.ReadingOptionKey: Any] = [
            .urlReadingFileURLsOnly: true,
            .urlReadingContentsConformToTypes: [UTType.image.identifier],
        ]
        if let url = (pboard.readObjects(forClasses: [NSURL.self], options: opts) as? [URL])?.first { return .file(url) }
        for type in [NSPasteboard.PasteboardType.png, .tiff, NSPasteboard.PasteboardType(UTType.jpeg.identifier)] {
            if let data = pboard.data(forType: type) { return .bytes(data) }
        }
        return nil
    }

    nonisolated enum Picture: Sendable {
        case file(URL)
        case bytes(Data)
    }

    static func read(_ picture: Picture) async -> Outcome {
        switch picture {
        case .file(let url): return await read(url: url)
        case .bytes(let data): return await read(data: data)
        }
    }
}
