import Foundation

struct ViewHierarchyRequest: Codable {
    let appIds: [String]
    let excludeKeyboardElements: Bool
}

struct AutomationSnapshotRequest: Codable {
    let appIds: [String]
    let mode: String
    let flat: Bool
    let interactiveOnly: Bool
    let fields: [String]
    let maxDepth: Int?
    let includeStatusBars: Bool
    let includeSafariWebViews: Bool
    let excludeKeyboardElements: Bool
    let sinceToken: String?
}

struct AutomationQuerySelector: Codable {
    let id: String?
    let text: String?
    let index: Int?
    let useFuzzyMatching: Bool
    let enabled: Bool?
    let checked: Bool?
    let focused: Bool?
    let selected: Bool?
}

struct AutomationQueryRequest: Codable {
    let appIds: [String]
    let selectors: [AutomationQuerySelector]
    let interactiveOnly: Bool
    let fields: [String]
    let maxDepth: Int?
    let includeStatusBars: Bool
    let includeSafariWebViews: Bool
    let excludeKeyboardElements: Bool
}

struct AutomationNode: Codable {
    let id: String?
    let text: String?
    let bounds: String?
    let enabled: Bool?
    let checked: Bool?
    let focused: Bool?
    let selected: Bool?
    let clickable: Bool?
    let depth: Int
}

struct AutomationSnapshotResponse: Codable {
    let source: String
    let mode: String
    let changed: Bool
    let token: String?
    let nodeCount: Int
    let nodes: [AutomationNode]
}

struct AutomationQueryMatchResponse: Codable {
    let selectorIndex: Int
    let matchCount: Int
    let nodes: [AutomationNode]
}

struct AutomationQueryResponse: Codable {
    let source: String
    let token: String?
    let matches: [AutomationQueryMatchResponse]
}
