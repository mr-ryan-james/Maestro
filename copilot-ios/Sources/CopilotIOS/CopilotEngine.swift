#if canImport(UIKit)
import Foundation
import UIKit
import QuartzCore

/// Result of an `awaitQuiescence` call. Encoded to JSON for the socket reply.
struct QuiescenceResult {
    let quiescent: Bool
    let phase: String
    let framesObserved: UInt32
    let signals: [String: String]
}

enum QuiescencePhase: String {
    case waitingForRunLoopDrain = "WAITING_FOR_RUNLOOP_DRAIN"
    case waitingForEpochsStable = "WAITING_FOR_EPOCHS_STABLE"
    case waitingForAsyncIdle = "WAITING_FOR_ASYNC_IDLE"
    case waitingForContent = "WAITING_FOR_CONTENT"
    case waitingForFirstFrame = "WAITING_FOR_FIRST_FRAME"
    case countingStableFrames = "COUNTING_STABLE_FRAMES"
    case quiescent = "QUIESCENT"
}

enum TransitionClass: String {
    case none
    case minor
    case screen
}

/// Event-driven settle detector. Instead of screenshot-diffing or AX polling, it watches
/// the run loop, layout/display invalidation epochs, in-flight network, and rendered
/// content, and only reports quiescent when the app is genuinely idle *with content on
/// screen* for N consecutive frames. The "content present" gate is what keeps it from
/// reporting ready on a blank-but-idle screen mid-bundle (the render-race).
@MainActor
final class CopilotEngine {
    static let shared = CopilotEngine()

    private let runLoopTracker = RunLoopTracker()
    private var displayLink: CADisplayLink?
    private var stableCounter = StableFrameCounter()
    private var observedFrames: UInt32 = 0
    private var firstFrameSeen = false
    private var lastEpochs: (UInt64, UInt64) = (0, 0)
    private var currentPhase: QuiescencePhase = .waitingForRunLoopDrain
    private var effectiveStableFrames: Int = 1
    private var installed = false
    // We drive the run loop ourselves in `pumpRunLoopSlice`, which keeps the CFRunLoop
    // observer from ever reporting `.beforeWaiting` during a wait. Track that we've pumped
    // (drained) at least one slice so the idle gate can be satisfied; the real settle work
    // is done by the epochs-stable / content-present / stable-frame gates.
    private var observedRunLoopDrain = false

    func install() {
        guard !installed else { return }
        installed = true
        runLoopTracker.install()
        EpochTracker.shared.install()
        TransitionTracker.shared.install()
        NetworkTracker.install()
        if displayLink == nil {
            let link = CADisplayLink(target: self, selector: #selector(onDisplayLink(_:)))
            link.isPaused = true
            link.add(to: .main, forMode: .common)
            displayLink = link
        }
    }

    /// Blocks (pumping the run loop) until the app is quiescent or the timeout elapses.
    /// `stableFrames` — consecutive identical frames required. Screen transitions demand
    /// more stability than minor updates.
    func awaitQuiescence(transitionClass: TransitionClass, timeoutMs: UInt32) -> QuiescenceResult {
        effectiveStableFrames = requiredStableFrames(for: transitionClass)
        stableCounter.reset()
        observedFrames = 0
        firstFrameSeen = false
        currentPhase = .waitingForRunLoopDrain
        lastEpochs = EpochTracker.shared.snapshot()
        observedRunLoopDrain = false
        TransitionTracker.shared.reset()

        // transitionClass == none is a no-op sync point: the caller does its own gating.
        if transitionClass == .none {
            return QuiescenceResult(quiescent: true, phase: QuiescencePhase.quiescent.rawValue, framesObserved: 0, signals: buildSignals())
        }

        displayLink?.isPaused = false
        let deadline = Date().addingTimeInterval(TimeInterval(timeoutMs) / 1000.0)
        while Date() < deadline {
            pumpRunLoopSlice(until: Date(timeIntervalSinceNow: 0.002))
            evaluate()
            if currentPhase == .quiescent {
                displayLink?.isPaused = true
                return QuiescenceResult(quiescent: true, phase: currentPhase.rawValue, framesObserved: observedFrames, signals: buildSignals())
            }
        }
        displayLink?.isPaused = true
        return QuiescenceResult(quiescent: false, phase: currentPhase.rawValue, framesObserved: observedFrames, signals: buildSignals())
    }

    func diagnostics() -> QuiescenceResult {
        QuiescenceResult(quiescent: currentPhase == .quiescent, phase: currentPhase.rawValue, framesObserved: observedFrames, signals: buildSignals())
    }

    @objc private func onDisplayLink(_ link: CADisplayLink) {
        observedFrames &+= 1
        firstFrameSeen = true
        let epochs = EpochTracker.shared.snapshot()
        let fingerprint = FrameFingerprint(
            layoutEpoch: epochs.0,
            displayEpoch: epochs.1,
            runLoopIdle: runLoopTracker.isIdle || observedRunLoopDrain,
            asyncIdle: NetworkTracker.activeRequests == 0,
            windowContentPresent: windowContentPresent()
        )
        _ = stableCounter.push(fingerprint)
        lastEpochs = epochs
    }

    private func evaluate() {
        if !(runLoopTracker.isIdle || observedRunLoopDrain) { currentPhase = .waitingForRunLoopDrain; return }

        currentPhase = .waitingForEpochsStable
        let epochs = EpochTracker.shared.snapshot()
        if epochs != lastEpochs { return }

        currentPhase = .waitingForAsyncIdle
        if NetworkTracker.activeRequests != 0 { return }

        currentPhase = .waitingForContent
        if !windowContentPresent() { return }

        currentPhase = .waitingForFirstFrame
        if !firstFrameSeen { return }

        currentPhase = .countingStableFrames
        if stableCounter.count >= effectiveStableFrames {
            currentPhase = .quiescent
        }
    }

    private func requiredStableFrames(for transitionClass: TransitionClass) -> Int {
        switch transitionClass {
        case .none: return 0
        case .minor: return 1
        case .screen: return 3
        }
    }

    private func pumpRunLoopSlice(until limit: Date) {
        _ = RunLoop.current.run(mode: .default, before: limit)
        let tracking = RunLoop.Mode(rawValue: "UITrackingRunLoopMode")
        _ = RunLoop.current.run(mode: tracking, before: limit)
        observedRunLoopDrain = true
    }

    /// True when a visible window has a root view controller whose view tree actually has
    /// rendered content — not just an empty window. This is the gate that prevents
    /// reporting "ready" on a blank screen while a lazy bundle is still mounting.
    private func windowContentPresent() -> Bool {
        let windows = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .filter { !$0.isHidden && $0.alpha > 0.01 }
        guard !windows.isEmpty else { return false }
        for window in windows {
            guard let root = window.rootViewController?.view else { continue }
            if renderedLeafCount(root, budget: 12) >= 3 { return true }
        }
        return false
    }

    /// Counts non-trivial rendered leaves up to a small budget. A blank shell (window +
    /// a container or two) stays under the threshold; a mounted screen clears it quickly.
    private func renderedLeafCount(_ view: UIView, budget: Int) -> Int {
        var count = 0
        if view.subviews.isEmpty && view.bounds.width > 1 && view.bounds.height > 1 && !view.isHidden && view.alpha > 0.01 {
            count += 1
        }
        for sub in view.subviews {
            if count >= budget { break }
            count += renderedLeafCount(sub, budget: budget - count)
        }
        return count
    }

    private func buildSignals() -> [String: String] {
        let epochs = EpochTracker.shared.snapshot()
        return [
            "runloopIdle": String(runLoopTracker.isIdle || observedRunLoopDrain),
            "epochsStable": String(epochs == lastEpochs),
            "activeNetworkRequests": String(NetworkTracker.activeRequests),
            "windowContentPresent": String(windowContentPresent()),
            "stableFrameCount": String(stableCounter.count),
            "effectiveStableFrames": String(effectiveStableFrames),
            "framesObserved": String(observedFrames),
            "observedNewViewController": String(TransitionTracker.shared.observedNewViewController),
        ]
    }
}
#endif
