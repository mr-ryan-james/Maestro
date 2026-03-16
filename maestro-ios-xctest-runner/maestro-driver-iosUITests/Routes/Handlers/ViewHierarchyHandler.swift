import FlyingFox
import CryptoKit
import XCTest
import os
import MaestroDriverLib

@MainActor
struct ViewHierarchyHandler: HTTPHandler {

    private let springboardApplication = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    private let snapshotMaxDepth = 60

    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )

    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> HTTPResponse {
        guard let requestBody = try? await JSONDecoder().decode(ViewHierarchyRequest.self, from: request.bodyData) else {
            return AppError(type: .precondition, message: "incorrect request body provided").httpResponse
        }

        do {
            let foregroundApp = RunningApp.getForegroundApp()
            guard let foregroundApp = foregroundApp else {
                NSLog("No foreground app found returning springboard app hierarchy")
                let springboardHierarchy = try elementHierarchy(xcuiElement: springboardApplication)
                let springBoardViewHierarchy = ViewHierarchy.init(axElement: springboardHierarchy, depth: springboardHierarchy.depth())
                let body = try JSONEncoder().encode(springBoardViewHierarchy)
                return HTTPResponse(statusCode: .ok, body: body)
            }
            NSLog("[Start] View hierarchy snapshot for \(foregroundApp)")
            let appViewHierarchy = try await getAppViewHierarchy(
                foregroundApp: foregroundApp,
                excludeKeyboardElements: requestBody.excludeKeyboardElements,
                includeStatusBars: true,
                includeSafariWebViews: true
            )
            let viewHierarchy = ViewHierarchy.init(axElement: appViewHierarchy, depth: appViewHierarchy.depth())
            
            NSLog("[Done] View hierarchy snapshot for \(foregroundApp) ")
            let body = try JSONEncoder().encode(viewHierarchy)
            return HTTPResponse(statusCode: .ok, body: body)
        } catch let error as AppError {
            NSLog("AppError in handleRequest, Error:\(error)");
            return error.httpResponse
        } catch let error {
            NSLog("Error in handleRequest, Error:\(error)");
            return AppError(message: "Snapshot failure while getting view hierarchy. Error: \(error.localizedDescription)").httpResponse
        }
    }

    func automationSnapshotResponse(_ requestBody: AutomationSnapshotRequest) async throws -> AutomationSnapshotResponse {
        let hierarchy = try await resolveAutomationHierarchy(
            excludeKeyboardElements: requestBody.excludeKeyboardElements,
            includeStatusBars: requestBody.includeStatusBars,
            includeSafariWebViews: requestBody.includeSafariWebViews
        )
        let fields = Set(requestBody.fields.isEmpty ? defaultAutomationFields : requestBody.fields)
        let nodes = hierarchy.flattenAutomationNodes(
            fields: fields,
            interactiveOnly: requestBody.interactiveOnly,
            maxDepth: requestBody.maxDepth
        )
        let token = automationToken(nodes)
        let changed = requestBody.sinceToken == nil || requestBody.sinceToken != token
        return AutomationSnapshotResponse(
            source: "ios_runner",
            mode: requestBody.mode,
            changed: changed,
            token: token,
            nodeCount: nodes.count,
            nodes: changed ? nodes : []
        )
    }

    func automationQueryResponse(_ requestBody: AutomationQueryRequest) async throws -> AutomationQueryResponse {
        let hierarchy = try await resolveAutomationHierarchy(
            excludeKeyboardElements: requestBody.excludeKeyboardElements,
            includeStatusBars: requestBody.includeStatusBars,
            includeSafariWebViews: requestBody.includeSafariWebViews
        )
        let fields = Set(requestBody.fields.isEmpty ? defaultAutomationFields : requestBody.fields)
        let nodes = hierarchy.flattenAutomationNodes(
            fields: fields,
            interactiveOnly: requestBody.interactiveOnly,
            maxDepth: requestBody.maxDepth
        )
        let token = automationToken(nodes)
        let matches = requestBody.selectors.enumerated().map { index, selector in
            let filtered = nodes.filter { selector.matches(node: $0) }
            let selectedNodes: [AutomationNode]
            if let selectorIndex = selector.index {
                if selectorIndex >= 0, selectorIndex < filtered.count {
                    selectedNodes = [filtered[selectorIndex]]
                } else {
                    selectedNodes = []
                }
            } else {
                selectedNodes = filtered
            }

            return AutomationQueryMatchResponse(
                selectorIndex: index,
                matchCount: selectedNodes.count,
                nodes: selectedNodes
            )
        }

        return AutomationQueryResponse(
            source: "ios_runner",
            token: token,
            matches: matches
        )
    }

    func getAppViewHierarchy(
        foregroundApp: XCUIApplication,
        excludeKeyboardElements: Bool,
        includeStatusBars: Bool,
        includeSafariWebViews: Bool
    ) async throws -> AXElement {
        let appHierarchy = try getHierarchyWithFallback(foregroundApp)
        await SystemPermissionHelper.handleSystemPermissionAlertIfNeeded(appHierarchy: appHierarchy, foregroundApp: foregroundApp)

        let statusBars = includeStatusBars
            ? (logger.measure(message: "Fetch status bar hierarchy") {
                fullStatusBars(springboardApplication)
            } ?? [])
            : []

        let safariWebViewHierarchy = includeSafariWebViews
            ? logger.measure(message: "Fetch Safari WebView hierarchy") {
                getSafariWebViewHierarchy()
            }
            : nil

        let deviceFrame = springboardApplication.frame
        let deviceAxFrame = [
            "X": Double(deviceFrame.minX),
            "Y": Double(deviceFrame.minY),
            "Width": Double(deviceFrame.width),
            "Height": Double(deviceFrame.height)
        ]
        let appFrame = appHierarchy.frame
        
        if deviceAxFrame != appFrame {
            guard
                let deviceWidth = deviceAxFrame["Width"], deviceWidth > 0,
                let deviceHeight = deviceAxFrame["Height"], deviceHeight > 0,
                let appWidth = appFrame["Width"], appWidth > 0,
                let appHeight = appFrame["Height"], appHeight > 0
            else {
                return AXElement(children: [appHierarchy, AXElement(children: statusBars), safariWebViewHierarchy].compactMap { $0 })
            }

            // Springboard always reports its frame in portrait dimensions (e.g. 1024×1366),
            // while a landscape app reports them swapped (1366×1024). Without this guard,
            // the difference would be misinterpreted as a window offset, shifting every
            // element's coordinates by hundreds of points in the wrong direction.
            let isSameAreaDifferentOrientation =
                abs(deviceWidth * deviceHeight - appWidth * appHeight) < 1.0
                && abs(deviceWidth - appHeight) < 1.0
                && abs(deviceHeight - appWidth) < 1.0

            if isSameAreaDifferentOrientation {
                NSLog("Skipping offset adjustment: device and app frames are same size but different orientation")
                return AXElement(children: [appHierarchy, AXElement(children: statusBars), safariWebViewHierarchy].compactMap { $0 })
            }

            let offsetX = deviceWidth - appWidth
            let offsetY = deviceHeight - appHeight
            let offset = WindowOffset(offsetX: offsetX, offsetY: offsetY)

            NSLog("Adjusting view hierarchy with offset: \(offset)")

            let adjustedAppHierarchy = expandElementSizes(appHierarchy, offset: offset)

            return AXElement(children: [adjustedAppHierarchy, AXElement(children: statusBars), safariWebViewHierarchy].compactMap { $0 })
        } else {
            return AXElement(children: [appHierarchy, AXElement(children: statusBars), safariWebViewHierarchy].compactMap { $0 })
        }
    }

    private func resolveAutomationHierarchy(
        excludeKeyboardElements: Bool,
        includeStatusBars: Bool,
        includeSafariWebViews: Bool
    ) async throws -> AXElement {
        if let foregroundApp = RunningApp.getForegroundApp() {
            return try await getAppViewHierarchy(
                foregroundApp: foregroundApp,
                excludeKeyboardElements: excludeKeyboardElements,
                includeStatusBars: includeStatusBars,
                includeSafariWebViews: includeSafariWebViews
            )
        }

        NSLog("No foreground app found returning springboard app hierarchy")
        return try elementHierarchy(xcuiElement: springboardApplication)
    }
    
    func expandElementSizes(_ element: AXElement, offset: WindowOffset) -> AXElement {
        let adjustedFrame: AXFrame = [
            "X": (element.frame["X"] ?? 0) + offset.offsetX,
            "Y": (element.frame["Y"] ?? 0) + offset.offsetY,
            "Width": element.frame["Width"] ?? 0,
            "Height": element.frame["Height"] ?? 0
        ]
        let adjustedChildren = element.children?.map { expandElementSizes($0, offset: offset) } ?? []
        
        return AXElement(
            identifier: element.identifier,
            frame: adjustedFrame,
            value: element.value,
            title: element.title,
            label: element.label,
            elementType: element.elementType,
            enabled: element.enabled,
            horizontalSizeClass: element.horizontalSizeClass,
            verticalSizeClass: element.verticalSizeClass,
            placeholderValue: element.placeholderValue,
            selected: element.selected,
            hasFocus: element.hasFocus,
            displayID: element.displayID,
            windowContextID: element.windowContextID,
            children: adjustedChildren
        )
    }

    func getHierarchyWithFallback(_ element: XCUIElement) throws -> AXElement {
        logger.info("Starting getHierarchyWithFallback for element.")

        do {
            let hierarchy = try elementHierarchy(xcuiElement: element)
            logger.info("Successfully retrieved element hierarchy (depth: \(hierarchy.depth())).")
            return hierarchy
        } catch let error {
            guard isIllegalArgumentError(error) else {
                NSLog("Snapshot failure, cannot return view hierarchy due to \(error)")
                if let nsError = error as NSError?,
                   nsError.domain == "com.apple.dt.XCTest.XCTFuture",
                   nsError.code == 1000,
                   nsError.localizedDescription.contains("Timed out while evaluating UI query") {
                    throw AppError(type: .timeout, message: error.localizedDescription)
                } else if let nsError = error as NSError?,
                           nsError.domain == "com.apple.dt.xctest.automation-support.error",
                           nsError.code == 6,
                           nsError.localizedDescription.contains("Unable to perform work on main run loop, process main thread busy for") {
                    throw AppError(type: .timeout, message: nsError.localizedDescription)
                } else {
                    throw AppError(message: error.localizedDescription)
                }
            }

            NSLog("Snapshot failure, getting recovery element for fallback")
            AXClientSwizzler.overwriteDefaultParameters["maxDepth"] = snapshotMaxDepth
            // In apps with bigger view hierarchys, calling
            // `XCUIApplication().snapshot().dictionaryRepresentation` or `XCUIApplication().allElementsBoundByIndex`
            // throws "Error kAXErrorIllegalArgument getting snapshot for element <AXUIElementRef 0x6000025eb660>"
            // We recover by selecting the first child of the app element,
            // which should be the window, and continue from there.

            let recoveryElement = try findRecoveryElement(element.children(matching: .any).firstMatch)
            let hierarchy = try getHierarchyWithFallback(recoveryElement)

            // When the application element is skipped, try to fetch
            // the keyboard, alert and other custom element hierarchies separately.
            if let element = element as? XCUIApplication {
                let keyboard = logger.measure(message: "Fetch keyboard hierarchy") {
                    keyboardHierarchy(element)
                }

                let alerts = logger.measure(message: "Fetch alert hierarchy") {
                    fullScreenAlertHierarchy(element)
                }

                let other = try logger.measure(message: "Fetch other custom element from window") {
                    try customWindowElements(element)
                }
                return AXElement(children: [
                    other,
                    keyboard,
                    alerts,
                    hierarchy
                ].compactMap { $0 })
            }

            return hierarchy
        }
    }

    private func isIllegalArgumentError(_ error: Error) -> Bool {
        error.localizedDescription.contains("Error kAXErrorIllegalArgument getting snapshot for element")
    }

    private func keyboardHierarchy(_ element: XCUIApplication) -> AXElement? {
        guard element.keyboards.firstMatch.exists else {
            return nil
        }
        
        let keyboard = element.keyboards.firstMatch
        return try? elementHierarchy(xcuiElement: keyboard)
    }
    
    private func customWindowElements(_ element: XCUIApplication) throws -> AXElement? {
        let windowElement = element.children(matching: .any).firstMatch
        if try windowElement.snapshot().children.count > 1 {
            return nil
        }
        return try? elementHierarchy(xcuiElement: windowElement)
    }

    func fullScreenAlertHierarchy(_ element: XCUIApplication) -> AXElement? {
        guard element.alerts.firstMatch.exists else {
            return nil
        }
        
        let alert = element.alerts.firstMatch
        return try? elementHierarchy(xcuiElement: alert)
    }
    
    func fullStatusBars(_ element: XCUIApplication) -> [AXElement]? {
        guard element.statusBars.firstMatch.exists else {
            return nil
        }
        
        let snapshots = try? element.statusBars.allElementsBoundByIndex.compactMap{ (statusBar) in
            try elementHierarchy(xcuiElement: statusBar)
        }
        
        return snapshots
    }
    
    /// Fetches the Safari WebView hierarchy for iOS 26+ where SFSafariViewController
    /// runs in a separate process (com.apple.SafariViewService).
    /// Returns nil if not on iOS 26+, Safari service is not running, or no webviews exist.
    private func getSafariWebViewHierarchy() -> AXElement? {
        let systemVersion = ProcessInfo.processInfo.operatingSystemVersion
        guard systemVersion.majorVersion >= 26 else {
            return nil
        }
        
        let safariWebService = XCUIApplication(bundleIdentifier: "com.apple.SafariViewService")
        
        let isRunning = safariWebService.state == .runningForeground || safariWebService.state == .runningBackground
        guard isRunning else {
            return nil
        }
        
        let webViewCount = safariWebService.webViews.count
        guard webViewCount > 0 else {
            return nil
        }
        
        NSLog("[Start] Fetching Safari WebView hierarchy (\(webViewCount) webview(s) detected)")
        
        do {
            AXClientSwizzler.overwriteDefaultParameters["maxDepth"] = snapshotMaxDepth
            let safariHierarchy = try elementHierarchy(xcuiElement: safariWebService)
            NSLog("[Done] Safari WebView hierarchy fetched successfully")
            return safariHierarchy
        } catch {
            NSLog("[Error] Failed to fetch Safari WebView hierarchy: \(error.localizedDescription)")
            return nil
        }
    }

    private func findRecoveryElement(_ element: XCUIElement) throws -> XCUIElement {
        if try element.snapshot().children.count > 1 {
            return element
        }
        let firstOtherElement = element.children(matching: .other).firstMatch
        if (firstOtherElement.exists) {
            return try findRecoveryElement(firstOtherElement)
        } else {
            return element
        }
    }

    private func elementHierarchy(xcuiElement: XCUIElement) throws -> AXElement {
        let snapshotDictionary = try xcuiElement.snapshot().dictionaryRepresentation
        return AXElement(snapshotDictionary)
    }

    private func truncateHierarchy(_ element: AXElement, remainingDepth: Int) -> AXElement {
        let truncatedChildren: [AXElement]
        if remainingDepth <= 0 {
            truncatedChildren = []
        } else {
            truncatedChildren = (element.children ?? []).map {
                truncateHierarchy($0, remainingDepth: remainingDepth - 1)
            }
        }

        return AXElement(
            identifier: element.identifier,
            frame: element.frame,
            value: element.value,
            title: element.title,
            label: element.label,
            elementType: element.elementType,
            enabled: element.enabled,
            horizontalSizeClass: element.horizontalSizeClass,
            verticalSizeClass: element.verticalSizeClass,
            placeholderValue: element.placeholderValue,
            selected: element.selected,
            hasFocus: element.hasFocus,
            displayID: element.displayID,
            windowContextID: element.windowContextID,
            children: truncatedChildren
        )
    }
}

@MainActor
struct AutomationSnapshotHandler: HTTPHandler {
    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> HTTPResponse {
        guard let requestBody = try? await JSONDecoder().decode(AutomationSnapshotRequest.self, from: request.bodyData) else {
            return AppError(type: .precondition, message: "incorrect request body provided").httpResponse
        }

        do {
            let response = try await ViewHierarchyHandler().automationSnapshotResponse(requestBody)
            let body = try JSONEncoder().encode(response)
            return HTTPResponse(statusCode: .ok, body: body)
        } catch let error as AppError {
            return error.httpResponse
        } catch {
            return AppError(message: "Snapshot failure while getting automation snapshot. Error: \(error.localizedDescription)").httpResponse
        }
    }
}

@MainActor
struct AutomationQueryHandler: HTTPHandler {
    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> HTTPResponse {
        guard let requestBody = try? await JSONDecoder().decode(AutomationQueryRequest.self, from: request.bodyData) else {
            return AppError(type: .precondition, message: "incorrect request body provided").httpResponse
        }

        do {
            let response = try await ViewHierarchyHandler().automationQueryResponse(requestBody)
            let body = try JSONEncoder().encode(response)
            return HTTPResponse(statusCode: .ok, body: body)
        } catch let error as AppError {
            return error.httpResponse
        } catch {
            return AppError(message: "Snapshot failure while querying automation elements. Error: \(error.localizedDescription)").httpResponse
        }
    }
}

private let defaultAutomationFields = [
    "id",
    "text",
    "bounds",
    "enabled",
    "checked",
    "focused",
    "selected",
    "clickable",
    "depth",
]

private let automationCheckableElementTypes: Set<Int> = [
    14,
    15,
]

private let automationInteractiveElementTypes: Set<Int> = [
    1,
    3,
    4,
    9,
    10,
    11,
    14,
    15,
    40,
]

private extension AXElement {
    func flattenAutomationNodes(
        fields: Set<String>,
        interactiveOnly: Bool,
        maxDepth: Int?,
        depth: Int = 0
    ) -> [AutomationNode] {
        if let maxDepth, depth > maxDepth {
            return []
        }

        let checked = automationCheckableElementTypes.contains(elementType) && value == "1"
        let text = title?.nonBlankAutomationValue
            ?? value?.nonBlankAutomationValue
            ?? label.nonBlankAutomationValue
        let node = AutomationNode(
            id: fields.contains("id") ? identifier.nonBlankAutomationValue : nil,
            text: fields.contains("text") ? text : nil,
            bounds: fields.contains("bounds") ? frame.boundsString : nil,
            enabled: fields.contains("enabled") ? enabled : nil,
            checked: fields.contains("checked") ? checked : nil,
            focused: fields.contains("focused") ? hasFocus : nil,
            selected: fields.contains("selected") ? selected : nil,
            clickable: fields.contains("clickable") ? isInteractiveAutomationElement : nil,
            depth: fields.contains("depth") ? depth : 0
        )

        let childNodes = (children ?? []).flatMap {
            $0.flattenAutomationNodes(
                fields: fields,
                interactiveOnly: interactiveOnly,
                maxDepth: maxDepth,
                depth: depth + 1
            )
        }

        let includeNode = !interactiveOnly
            || node.clickable == true
            || !(node.id?.isEmpty ?? true)
            || !(node.text?.isEmpty ?? true)

        return includeNode ? [node] + childNodes : childNodes
    }

    var isInteractiveAutomationElement: Bool {
        return automationInteractiveElementTypes.contains(elementType) || !(identifier.nonBlankAutomationValue?.isEmpty ?? true)
    }
}

private extension AXFrame {
    var boundsString: String {
        let left = Int(x)
        let top = Int(y)
        let right = Int(x + width)
        let bottom = Int(y + height)
        return "[\(left),\(top)][\(right),\(bottom)]"
    }
}

private extension AutomationQuerySelector {
    func matches(node: AutomationNode) -> Bool {
        let matchesId: Bool
        if let id = id?.nonBlankAutomationValue {
            let nodeId = node.id ?? ""
            matchesId = useFuzzyMatching
                ? nodeId.localizedCaseInsensitiveContains(id)
                : nodeId == id
        } else {
            matchesId = true
        }

        let matchesText: Bool
        if let text = text?.nonBlankAutomationValue {
            let nodeText = node.text ?? ""
            matchesText = useFuzzyMatching
                ? nodeText.localizedCaseInsensitiveContains(text)
                : nodeText == text
        } else {
            matchesText = true
        }

        return matchesId
            && matchesText
            && (enabled.map { node.enabled == $0 } ?? true)
            && (checked.map { node.checked == $0 } ?? true)
            && (focused.map { node.focused == $0 } ?? true)
            && (selected.map { node.selected == $0 } ?? true)
    }
}

private extension String {
    var nonBlankAutomationValue: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

private func automationToken(_ nodes: [AutomationNode]) -> String {
    let normalized = nodes.map { node in
        let enabledValue = node.enabled.map { String(describing: $0) } ?? ""
        let checkedValue = node.checked.map { String(describing: $0) } ?? ""
        let focusedValue = node.focused.map { String(describing: $0) } ?? ""
        let selectedValue = node.selected.map { String(describing: $0) } ?? ""
        let clickableValue = node.clickable.map { String(describing: $0) } ?? ""

        return [
            node.id ?? "",
            node.text ?? "",
            node.bounds ?? "",
            enabledValue,
            checkedValue,
            focusedValue,
            selectedValue,
            clickableValue,
            String(node.depth),
        ].joined(separator: "\u{001f}")
    }.joined(separator: "\n")

    let digest = SHA256.hash(data: Data(normalized.utf8))
    return digest.map { String(format: "%02x", $0) }.joined()
}
