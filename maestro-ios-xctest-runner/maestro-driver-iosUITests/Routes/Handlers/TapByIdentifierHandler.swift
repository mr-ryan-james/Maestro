import FlyingFox
import XCTest
import os

struct TapByIdentifierRequest: Decodable {
    let identifier: String
    let bundleId: String
    let timeout: Double?
}

struct TapByIdentifierResponse: Encodable {
    let found: Bool
    let frame: [String: Double]?
    let message: String?
}

@MainActor
struct TapByIdentifierHandler: HTTPHandler {
    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )

    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        guard let requestBody = try? await JSONDecoder().decode(TapByIdentifierRequest.self, from: request.bodyData) else {
            return AppError(type: .precondition, message: "incorrect request body for tapByIdentifier").httpResponse
        }

        let timeout = requestBody.timeout ?? 5.0
        let app = XCUIApplication(bundleIdentifier: requestBody.bundleId)

        logger.info("tapByIdentifier: looking for '\(requestBody.identifier)' in app '\(requestBody.bundleId)' (timeout: \(timeout)s)")

        // Direct XCUIElement query — does NOT use snapshot()
        // This is the key hypothesis: descendants(matching:) may bypass
        // Apple's 60-level AX snapshot depth limit.
        let element = app.descendants(matching: .any)
            .matching(identifier: requestBody.identifier)
            .firstMatch

        if element.waitForExistence(timeout: timeout) {
            let frame = element.frame
            logger.info("tapByIdentifier: FOUND '\(requestBody.identifier)' at frame \(String(describing: frame))")

            element.tap()
            logger.info("tapByIdentifier: tapped '\(requestBody.identifier)'")

            let resp = TapByIdentifierResponse(
                found: true,
                frame: [
                    "x": frame.origin.x,
                    "y": frame.origin.y,
                    "width": frame.size.width,
                    "height": frame.size.height
                ],
                message: "Tapped element"
            )
            let data = try JSONEncoder().encode(resp)
            return HTTPResponse(statusCode: .ok, body: data)
        } else {
            logger.info("tapByIdentifier: NOT FOUND '\(requestBody.identifier)' after \(timeout)s")

            let resp = TapByIdentifierResponse(
                found: false,
                frame: nil,
                message: "Element with identifier '\(requestBody.identifier)' not found after \(timeout)s"
            )
            let data = try JSONEncoder().encode(resp)
            return HTTPResponse(statusCode: .notFound, body: data)
        }
    }
}
