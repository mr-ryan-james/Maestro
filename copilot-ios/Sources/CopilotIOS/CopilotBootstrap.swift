#if canImport(UIKit)
import Foundation
import UIKit

/// C-callable entrypoint invoked by the dylib's `__attribute__((constructor))` bootstrap
/// (see CBootstrap/bootstrap.c). Runs on the loading thread very early — before
/// UIApplication exists — so it only reads env and defers the actual engine install
/// (and, once implemented, the socket server) onto the main queue.
@_cdecl("maestro_copilot_start")
public func maestro_copilot_start() {
    NSLog("[maestro-copilot] hello — dylib loaded (v1)")
    let env = ProcessInfo.processInfo.environment
    let port = env["MAESTRO_COPILOT_PORT"]
    NSLog("[maestro-copilot] MAESTRO_COPILOT_PORT=\(port ?? "<unset>")")

    DispatchQueue.main.async {
        CopilotEngine.shared.install()
        NSLog("[maestro-copilot] quiescence engine installed")
        if let port, let portValue = UInt16(port) {
            CopilotSocketServer.shared.start(port: portValue)
        } else {
            NSLog("[maestro-copilot] no MAESTRO_COPILOT_PORT set; socket server not started")
        }
    }
}
#endif
