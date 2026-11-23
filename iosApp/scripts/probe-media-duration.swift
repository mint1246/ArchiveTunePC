// Metadata-only simulator test probe. It does not launch or control the app UI.
import AVFoundation
import CoreMedia
import Foundation
import Darwin

guard CommandLine.arguments.count >= 2 else {
    fputs("Usage: probe-media-duration <local-audio-file>\n", stderr)
    exit(2)
}
// Signed URLs are supplied through stdin, never persisted or printed.
let source: URL
if CommandLine.arguments[1] == "--stdin-url" {
    guard let input = readLine(), let url = URL(string: input), url.scheme == "https" else { exit(2) }
    source = url
} else {
    source = URL(fileURLWithPath: CommandLine.arguments[1])
}
let precise = CommandLine.arguments.dropFirst(2).contains("precise")
let asset = AVURLAsset(url: source, options: [AVURLAssetPreferPreciseDurationAndTimingKey: precise])
Task {
    do {
        let duration = try await asset.load(.duration)
        print("AVAsset duration: value=\(duration.value), timescale=\(duration.timescale), seconds=\(CMTimeGetSeconds(duration))")
        let tracks = try await asset.loadTracks(withMediaType: .audio)
        for track in tracks {
            let range = try await track.load(.timeRange)
            print("Audio track duration: value=\(range.duration.value), timescale=\(range.duration.timescale), seconds=\(CMTimeGetSeconds(range.duration))")
        }
        let known = CMTime(value: 10_220_028, timescale: 44_100)
        print("CoreMedia conversion: \(CMTimeGetSeconds(known))")
        exit(0)
    } catch {
        // Never include the source URL or media-request metadata in output.
        let failure = error as NSError
        fputs("Metadata probe failed: \(failure.domain) / \(failure.code)\n", stderr)
        exit(1)
    }
}
dispatchMain()
