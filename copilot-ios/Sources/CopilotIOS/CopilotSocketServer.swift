#if canImport(UIKit)
import Foundation
import Darwin

/// Localhost socket server speaking a versioned, length-prefixed JSON protocol:
///   frame = 4-byte big-endian length + JSON body.
/// Requests:
///   {"cmd":"hello"}                                               -> {"version":1,"ok":true}
///   {"cmd":"awaitQuiescence","transitionClass":"screen","timeoutMs":15000}
///                                                                 -> {"quiescent":bool,"phase":..,"framesObserved":..,"signals":{..}}
///   {"cmd":"getSignals"}                                          -> {"quiescent":bool,"phase":..,"signals":{..}}
/// The accept/read loop runs on a background thread; `awaitQuiescence` is dispatched
/// synchronously onto the main thread because it pumps the main run loop.
final class CopilotSocketServer {
    static let shared = CopilotSocketServer()
    static let protocolVersion = 1

    private var listenFD: Int32 = -1
    private var started = false

    func start(port: UInt16) {
        guard !started else { return }
        started = true
        Thread.detachNewThread { [weak self] in
            self?.runAcceptLoop(port: port)
        }
    }

    private func runAcceptLoop(port: UInt16) {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { NSLog("[maestro-copilot] socket() failed"); return }
        var yes: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")

        let bindResult = withUnsafePointer(to: &addr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                bind(fd, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bindResult == 0 else { NSLog("[maestro-copilot] bind() failed on port \(port)"); close(fd); return }
        guard listen(fd, 4) == 0 else { NSLog("[maestro-copilot] listen() failed"); close(fd); return }
        listenFD = fd
        NSLog("[maestro-copilot] socket server listening on 127.0.0.1:\(port)")

        while true {
            let client = accept(fd, nil, nil)
            if client < 0 { continue }
            handleConnection(client)
            close(client)
        }
    }

    private func handleConnection(_ client: Int32) {
        while let body = readFrame(client) {
            let response = handleRequest(body)
            if !writeFrame(client, response) { break }
        }
    }

    // MARK: - Request dispatch

    private func handleRequest(_ body: Data) -> Data {
        guard let obj = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
              let cmd = obj["cmd"] as? String else {
            return encode(["ok": false, "error": "bad request"])
        }

        switch cmd {
        case "hello":
            return encode(["version": Self.protocolVersion, "ok": true])

        case "getSignals":
            let result = runOnMain { CopilotEngine.shared.diagnostics() }
            return encode(resultToDict(result))

        case "awaitQuiescence":
            let transitionClass = TransitionClass(rawValue: (obj["transitionClass"] as? String) ?? "minor") ?? .minor
            let timeoutMs = UInt32((obj["timeoutMs"] as? Int) ?? 15000)
            let result = runOnMain { CopilotEngine.shared.awaitQuiescence(transitionClass: transitionClass, timeoutMs: timeoutMs) }
            return encode(resultToDict(result))

        default:
            return encode(["ok": false, "error": "unknown cmd \(cmd)"])
        }
    }

    private func resultToDict(_ result: QuiescenceResult) -> [String: Any] {
        [
            "quiescent": result.quiescent,
            "phase": result.phase,
            "framesObserved": Int(result.framesObserved),
            "signals": result.signals,
        ]
    }

    /// Runs `work` on the main thread/actor. `awaitQuiescence` pumps the main run loop, so
    /// it must not execute on the socket's background thread. The closure is `@MainActor`;
    /// we hop to the main queue and assert isolation there.
    private func runOnMain<T>(_ work: @escaping @MainActor () -> T) -> T {
        if Thread.isMainThread {
            return MainActor.assumeIsolated { work() }
        }
        var out: T!
        DispatchQueue.main.sync {
            out = MainActor.assumeIsolated { work() }
        }
        return out
    }

    // MARK: - Framing

    private func encode(_ dict: [String: Any]) -> Data {
        (try? JSONSerialization.data(withJSONObject: dict)) ?? Data("{}".utf8)
    }

    private func readFrame(_ fd: Int32) -> Data? {
        guard let header = readExactly(fd, 4) else { return nil }
        let length = header.withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }
        guard length > 0, length < 1_000_000 else { return nil }
        return readExactly(fd, Int(length))
    }

    private func readExactly(_ fd: Int32, _ count: Int) -> Data? {
        var buffer = Data(count: count)
        var received = 0
        let ok = buffer.withUnsafeMutableBytes { raw -> Bool in
            let base = raw.baseAddress!
            while received < count {
                let n = recv(fd, base + received, count - received, 0)
                if n <= 0 { return false }
                received += n
            }
            return true
        }
        return ok ? buffer : nil
    }

    @discardableResult
    private func writeFrame(_ fd: Int32, _ body: Data) -> Bool {
        var length = UInt32(body.count).bigEndian
        let header = Data(bytes: &length, count: 4)
        return writeAll(fd, header) && writeAll(fd, body)
    }

    private func writeAll(_ fd: Int32, _ data: Data) -> Bool {
        data.withUnsafeBytes { raw -> Bool in
            let base = raw.baseAddress!
            var sent = 0
            while sent < data.count {
                let n = send(fd, base + sent, data.count - sent, 0)
                if n <= 0 { return false }
                sent += n
            }
            return true
        }
    }
}
#endif
