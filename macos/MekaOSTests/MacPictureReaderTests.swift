import AppKit
@preconcurrency import MekaKit
@testable import MekaOS
import XCTest

/// Screenshots and photos, slice 1: the Mac reads a picture's words on the device (Vision) and tidies them with the
/// core's rules; a picture with no words, or bytes that aren't a picture, say so instead.
final class MacPictureReaderTests: XCTestCase {
    private func png(_ lines: [String]) -> Data {
        let size = NSSize(width: 1200, height: 120 * lines.count + 80)
        let image = NSImage(size: size, flipped: false) { rect in
            NSColor.white.setFill()
            rect.fill()
            let attrs: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: 56), .foregroundColor: NSColor.black]
            for (i, line) in lines.enumerated() {
                (line as NSString).draw(at: NSPoint(x: 40, y: size.height - CGFloat(120 * (i + 1))), withAttributes: attrs)
            }
            return true
        }
        let rep = NSBitmapImageRep(data: image.tiffRepresentation!)!
        return rep.representation(using: .png, properties: [:])!
    }

    func testAPicturesWordsAreReadAndTheStatusBarDropped() async {
        let outcome = await MacPictureReader.read(data: png(["14:02", "Dentist appointment", "Thursday at three"]))
        guard case .words(let text) = outcome else { return XCTFail("expected words, got \(outcome)") }
        XCTAssertTrue(text.contains("Dentist"), text)
        XCTAssertFalse(text.contains("14:02"), text)
    }

    func testABlankPictureAndNotAPictureSaySo() async {
        guard case .nothing(let blank) = await MacPictureReader.read(data: png([])) else { return XCTFail("expected nothing") }
        XCTAssertEqual(blank, PictureTextRules.shared.NOTHING_LINE)
        guard case .nothing(let junk) = await MacPictureReader.read(data: Data("not a picture".utf8)) else { return XCTFail("expected nothing") }
        XCTAssertEqual(junk, PictureTextRules.shared.COULD_NOT_OPEN_LINE)
    }
}
