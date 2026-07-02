#if canImport(UIKit)
import Foundation
import os

/// Minimal thread-safe 64-bit counter. Self-contained (no C ring-buffer dependency)
/// so the copilot dylib links against nothing but the iOS SDK. Epoch marks may arrive
/// off the main thread (e.g. `setNeedsDisplay` from a background layer), so guard with
/// an unfair lock rather than assuming main-thread isolation.
final class CopilotAtomicCounter64 {
    private let lock = OSAllocatedUnfairLock(initialState: UInt64(0))

    init(initialValue: UInt64 = 0) {
        lock.withLock { $0 = initialValue }
    }

    @discardableResult
    func increment() -> UInt64 {
        lock.withLock { value in
            value &+= 1
            return value
        }
    }

    func load() -> UInt64 {
        lock.withLock { $0 }
    }
}
#endif
