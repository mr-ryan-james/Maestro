import Foundation
import FlyingFox
import os
import Darwin

@MainActor
struct ShutdownHandler: HTTPHandler {

    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )

    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        logger.info("Shutdown request received. Exiting XCTest runner process.")

        Task.detached {
            usleep(100_000)
            exit(0)
        }

        return HTTPResponse(statusCode: .ok)
    }
}
