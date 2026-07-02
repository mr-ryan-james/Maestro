import FlyingFox
import Foundation

enum Route: String, CaseIterable {
    case runningApp
    case swipe
    case swipeV2
    case inputText
    case touch
    case screenshot
    case isScreenStatic
    case pressKey
    case pressButton
    case eraseText
    case deviceInfo
    case setOrientation
    case setPermissions
    case viewHierarchy
    case automationSnapshot
    case queryAutomationElements
    case status
    case keyboard
    case launchApp
    case terminateApp
    case shutdown
    case tapByIdentifier

    func toHTTPRoute() -> HTTPRoute {
        return HTTPRoute(rawValue)
    }
}

struct XCTestHTTPServer {
    func start() async throws {
        configureAXTimeoutIfRequested()
        let port = ProcessInfo.processInfo.environment["PORT"]?.toUInt16()
        let server = HTTPServer(address: try .inet(ip4: "127.0.0.1", port: port ?? 22087), timeout: 100)
        
        for route in Route.allCases {
            let handler = await RouteHandlerFactory.createRouteHandler(route: route)
            await server.appendRoute(route.toHTTPRoute(), to: handler)
        }

        try await server.run()
    }

    /// Opt-in: when MAESTRO_IOS_AX_TIMEOUT_SECONDS is set to a positive value, raise the
    /// accessibility snapshot timeout so a single snapshot can wait through a blocked app
    /// main thread (e.g. Metro bundling a heavy screen) instead of failing at the framework
    /// default (~30s). Unset ⇒ behavior unchanged. Private-API failure is non-fatal.
    private func configureAXTimeoutIfRequested() {
        guard let raw = ProcessInfo.processInfo.environment["MAESTRO_IOS_AX_TIMEOUT_SECONDS"],
              let seconds = Double(raw), seconds > 0 else {
            return
        }
        do {
            try AXClientProxy.sharedClient().setAXTimeoutSeconds(seconds)
            NSLog("Configured AX snapshot timeout to \(seconds)s via MAESTRO_IOS_AX_TIMEOUT_SECONDS")
        } catch {
            NSLog("Failed to set AX snapshot timeout (\(seconds)s); continuing with framework default. Error: \(error.localizedDescription)")
        }
    }
}
