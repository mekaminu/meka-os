import CoreGraphics
import ImageIO
@preconcurrency import MekaKit
@testable import MekaOS
import UniformTypeIdentifiers
import XCTest

/// The Vault's copies on the Mac (Vault slice 2b): a picked photo comes back as a JPEG no longer than the long side,
/// a small PDF as it is, and anything else is refused with the core's words.
final class MacVaultCopyTests: XCTestCase {
    private func temp(_ name: String) -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "-" + name)
    }

    private func writePNG(width: Int, height: Int, to url: URL) throws {
        let ctx = try XCTUnwrap(CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ))
        ctx.setFillColor(CGColor(red: 0.8, green: 0.6, blue: 0.2, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let image = try XCTUnwrap(ctx.makeImage())
        let dest = try XCTUnwrap(CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil))
        CGImageDestinationAddImage(dest, image, nil)
        XCTAssertTrue(CGImageDestinationFinalize(dest))
    }

    func testABigPhotoBecomesAJpegWithinTheLongSide() async throws {
        let url = temp("scan.png")
        defer { try? FileManager.default.removeItem(at: url) }
        try writePNG(width: 3000, height: 1200, to: url)
        let picked = await MacVaultCopy.prepare(url: url)
        XCTAssertNil(picked.problem)
        XCTAssertEqual(picked.type, VaultFileRules.shared.JPEG)
        let data = try XCTUnwrap(picked.base64.flatMap { Data(base64Encoded: $0) })
        let source = try XCTUnwrap(CGImageSourceCreateWithData(data as CFData, nil))
        let props = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any])
        XCTAssertEqual(props[kCGImagePropertyPixelWidth] as? Int, 2000)
    }

    func testAPdfIsKeptAsItIsAndOtherFilesAreRefused() async throws {
        let pdf = temp("policy.pdf")
        let text = temp("notes.txt")
        defer { try? FileManager.default.removeItem(at: pdf); try? FileManager.default.removeItem(at: text) }
        let bytes = Data("%PDF-1.4\n%%EOF\n".utf8)
        try bytes.write(to: pdf)
        try Data("hello".utf8).write(to: text)
        let p = await MacVaultCopy.prepare(url: pdf)
        XCTAssertEqual(p.type, VaultFileRules.shared.PDF)
        XCTAssertEqual(p.base64, bytes.base64EncodedString())
        let t = await MacVaultCopy.prepare(url: text)
        XCTAssertNil(t.base64)
        XCTAssertEqual(t.problem, VaultFileRules.shared.NOT_TYPE)
    }
}
