#if canImport(UIKit)
import Foundation
import UIKit
import QuartzCore
import ObjectiveC.runtime
import os

// Ported from Celestial's Quiescence trackers, self-contained for the Maestro copilot.
// Each tracker installs cheap hooks (a run-loop observer + a few method swizzles) that
// let the engine tell whether the app is genuinely idle or still churning (the case that
// produces a blank-but-mounting screen during a lazy Metro bundle).

// MARK: - Run loop

@MainActor
final class RunLoopTracker {
    private(set) var isIdle = false
    private var observer: CFRunLoopObserver?

    func install() {
        guard observer == nil else { return }
        var context = CFRunLoopObserverContext(
            version: 0,
            info: Unmanaged.passUnretained(self).toOpaque(),
            retain: nil, release: nil, copyDescription: nil
        )
        observer = CFRunLoopObserverCreate(
            kCFAllocatorDefault,
            CFRunLoopActivity.allActivities.rawValue,
            true, 0,
            { _, activity, info in
                let tracker = Unmanaged<RunLoopTracker>.fromOpaque(info!).takeUnretainedValue()
                switch activity {
                case .beforeWaiting: tracker.isIdle = true
                default: tracker.isIdle = false
                }
            },
            &context
        )
        if let observer { CFRunLoopAddObserver(CFRunLoopGetMain(), observer, .commonModes) }
    }
}

// MARK: - Layout / display invalidation epochs

@MainActor
final class EpochTracker {
    static let shared = EpochTracker()
    private let layoutEpoch = CopilotAtomicCounter64()
    private let displayEpoch = CopilotAtomicCounter64()
    private var installed = false

    func install() {
        guard !installed else { return }
        installed = true
        copilotSwizzle(UIView.self, #selector(UIView.setNeedsLayout), #selector(UIView.copilot_setNeedsLayout))
        copilotSwizzle(UIView.self, NSSelectorFromString("setNeedsDisplay"), #selector(UIView.copilot_setNeedsDisplay))
    }

    func markLayout() { _ = layoutEpoch.increment() }
    func markDisplay() { _ = displayEpoch.increment() }
    func snapshot() -> (UInt64, UInt64) { (layoutEpoch.load(), displayEpoch.load()) }
}

extension UIView {
    @objc dynamic func copilot_setNeedsLayout() {
        MainActor.assumeIsolated { EpochTracker.shared.markLayout() }
        copilot_setNeedsLayout()
    }

    @objc dynamic func copilot_setNeedsDisplay() {
        MainActor.assumeIsolated { EpochTracker.shared.markDisplay() }
        copilot_setNeedsDisplay()
    }
}

// MARK: - View-controller transitions

@MainActor
final class TransitionTracker {
    static let shared = TransitionTracker()
    private var _observedNewViewController = false
    private var _currentViewControllerClass: String?
    private var installed = false

    var observedNewViewController: Bool { _observedNewViewController }
    var currentViewControllerClass: String? { _currentViewControllerClass }

    func install() {
        guard !installed else { return }
        installed = true
        copilotSwizzle(UIViewController.self, #selector(UIViewController.viewDidAppear(_:)), #selector(UIViewController.copilot_viewDidAppear(_:)))
    }

    func reset() {
        _observedNewViewController = false
        _currentViewControllerClass = nil
    }

    func record(_ viewController: UIViewController) {
        let newClass = String(describing: type(of: viewController))
        if _currentViewControllerClass != nil && _currentViewControllerClass != newClass {
            _observedNewViewController = true
        }
        _currentViewControllerClass = newClass
    }
}

extension UIViewController {
    @objc dynamic func copilot_viewDidAppear(_ animated: Bool) {
        MainActor.assumeIsolated { TransitionTracker.shared.record(self) }
        copilot_viewDidAppear(animated)
    }
}

// MARK: - In-flight network

private struct NetworkTrackerState {
    var installed = false
    var trackedTaskIDs: Set<ObjectIdentifier> = []
    var taskStartTimes: [ObjectIdentifier: CFAbsoluteTime] = [:]
}

/// Counts in-flight URLSession tasks, excluding websockets and any task running longer
/// than `longLivedThresholdSeconds` (SSE / long-poll), which would otherwise wedge
/// quiescence forever.
enum NetworkTracker {
    private static let state = OSAllocatedUnfairLock(initialState: NetworkTrackerState())
    private static let observerAssociationToken = 0
    private static let longLivedThresholdSeconds: CFTimeInterval = 10.0

    static var activeRequests: Int {
        let now = CFAbsoluteTimeGetCurrent()
        return state.withLock { snapshot in
            var shortLived = 0
            for taskID in snapshot.trackedTaskIDs {
                if let start = snapshot.taskStartTimes[taskID], (now - start) < longLivedThresholdSeconds {
                    shortLived += 1
                }
            }
            return shortLived
        }
    }

    static func install() {
        let shouldInstall = state.withLock { snapshot -> Bool in
            guard !snapshot.installed else { return false }
            snapshot.installed = true
            return true
        }
        guard shouldInstall else { return }
        copilotSwizzle(URLSessionTask.self, #selector(URLSessionTask.resume), #selector(URLSessionTask.copilot_resume))
    }

    static func track(task: URLSessionTask) {
        if task is URLSessionWebSocketTask { return }
        let taskID = ObjectIdentifier(task)
        let now = CFAbsoluteTimeGetCurrent()
        let shouldStart = state.withLock { snapshot -> Bool in
            guard snapshot.trackedTaskIDs.insert(taskID).inserted else { return false }
            snapshot.taskStartTimes[taskID] = now
            return true
        }
        guard shouldStart else { return }
        let observer = CopilotTaskObserver { finish(taskID: taskID) }
        withUnsafePointer(to: observerAssociationToken) { key in
            objc_setAssociatedObject(task, key, observer, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        }
        observer.start(task)
    }

    private static func finish(taskID: ObjectIdentifier) {
        state.withLock { snapshot in
            guard snapshot.trackedTaskIDs.remove(taskID) != nil else { return }
            snapshot.taskStartTimes.removeValue(forKey: taskID)
        }
    }
}

private final class CopilotTaskObserver: NSObject {
    private var observation: NSKeyValueObservation?
    private let completion: @Sendable () -> Void

    init(completion: @escaping @Sendable () -> Void) { self.completion = completion }

    func start(_ task: URLSessionTask) {
        let completion = completion
        observation = task.observe(\.state, options: [.new]) { task, _ in
            if task.state == .completed || task.state == .canceling { completion() }
        }
    }
}

extension URLSessionTask {
    @objc dynamic func copilot_resume() {
        NetworkTracker.track(task: self)
        copilot_resume()
    }
}

// MARK: - Stable-frame fingerprint

struct FrameFingerprint: Equatable {
    let layoutEpoch: UInt64
    let displayEpoch: UInt64
    let runLoopIdle: Bool
    let asyncIdle: Bool
    let windowContentPresent: Bool
}

struct StableFrameCounter {
    private(set) var previous: FrameFingerprint?
    private(set) var count: Int = 0

    mutating func push(_ fingerprint: FrameFingerprint) -> Int {
        if previous == fingerprint {
            count += 1
        } else {
            previous = fingerprint
            count = 0
        }
        return count
    }

    mutating func reset() {
        previous = nil
        count = 0
    }
}

// MARK: - Swizzle helper

func copilotSwizzle(_ cls: AnyClass, _ original: ObjectiveC.Selector, _ replacement: ObjectiveC.Selector) {
    guard let originalMethod = class_getInstanceMethod(cls, original),
          let replacementMethod = class_getInstanceMethod(cls, replacement) else { return }

    let didAddOriginal = class_addMethod(
        cls, original,
        method_getImplementation(replacementMethod),
        method_getTypeEncoding(replacementMethod)
    )
    if didAddOriginal {
        class_replaceMethod(
            cls, replacement,
            method_getImplementation(originalMethod),
            method_getTypeEncoding(originalMethod)
        )
    } else {
        method_exchangeImplementations(originalMethod, replacementMethod)
    }
}
#endif
