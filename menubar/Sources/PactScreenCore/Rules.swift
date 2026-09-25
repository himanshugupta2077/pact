import Foundation

public struct Bucket: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String

    public init(id: String, name: String) {
        self.id = id
        self.name = name
    }

    public static let defaults: [Bucket] = [
        Bucket(id: "work", name: "Work"),
        Bucket(id: "entertainment", name: "Entertainment"),
        Bucket(id: "gaming", name: "Gaming"),
        Bucket(id: "communication", name: "Communication"),
    ]

    public static let other = Bucket(id: "other", name: "Other")

    public var menuShort: String {
        switch id {
        case "work": return "W"
        case "entertainment": return "E"
        case "gaming": return "G"
        case "communication": return "C"
        case "other": return "Other"
        default:
            return name.first.map { String($0).uppercased() } ?? "?"
        }
    }

    public static func isValidId(_ id: String) -> Bool {
        guard id != other.id, let first = id.first, first.isLetter, id.count <= 32 else { return false }
        return id.allSatisfy { $0.isLetter || $0.isNumber || $0 == "-" }
    }

    public static func slug(_ name: String) -> String {
        var output = ""
        var dash = false
        for scalar in name.lowercased().unicodeScalars {
            if CharacterSet.alphanumerics.contains(scalar) {
                output.unicodeScalars.append(scalar)
                dash = false
            } else if !dash, !output.isEmpty {
                output.append("-")
                dash = true
            }
        }
        while output.last == "-" {
            output.removeLast()
        }
        return String(output.prefix(24))
    }
}

public struct Preferences: Codable, Equatable, Sendable {
    public var assignments: [String: String]
    public var buckets: [Bucket]
    public var showTotal: Bool
    public var menuBucketIds: [String]
    public var showDevices: Bool
    public var deviceLabels: [String: String]
    public var openAtLogin: Bool
    public var ssh: String

    public init(
        assignments: [String: String],
        buckets: [Bucket],
        showTotal: Bool,
        menuBucketIds: [String],
        showDevices: Bool,
        deviceLabels: [String: String],
        openAtLogin: Bool,
        ssh: String
    ) {
        self.assignments = assignments
        self.buckets = buckets
        self.showTotal = showTotal
        self.menuBucketIds = menuBucketIds
        self.showDevices = showDevices
        self.deviceLabels = deviceLabels
        self.openAtLogin = openAtLogin
        self.ssh = ssh
    }

    public static let standard = Preferences(
        assignments: [:],
        buckets: Bucket.defaults,
        showTotal: true,
        menuBucketIds: [],
        showDevices: false,
        deviceLabels: [:],
        openAtLogin: true,
        ssh: "pact@pact-host"
    )

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        assignments = try container.decodeIfPresent([String: String].self, forKey: .assignments) ?? [:]
        let stored = try container.decodeIfPresent([Bucket].self, forKey: .buckets) ?? []
        buckets = Preferences.mergingDefaults(stored)
        showTotal = try container.decodeIfPresent(Bool.self, forKey: .showTotal) ?? true
        menuBucketIds = try container.decodeIfPresent([String].self, forKey: .menuBucketIds) ?? []
        showDevices = try container.decodeIfPresent(Bool.self, forKey: .showDevices) ?? false
        deviceLabels = try container.decodeIfPresent([String: String].self, forKey: .deviceLabels) ?? [:]
        openAtLogin = try container.decodeIfPresent(Bool.self, forKey: .openAtLogin) ?? true
        let host = try container.decodeIfPresent(String.self, forKey: .ssh) ?? "pact@pact-host"
        ssh = SSHTarget.isValid(host) ? host : "pact@pact-host"
    }

    private enum CodingKeys: String, CodingKey {
        case assignments, buckets, showTotal, menuBucketIds, showDevices, deviceLabels, openAtLogin, ssh
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(assignments, forKey: .assignments)
        try container.encode(buckets, forKey: .buckets)
        try container.encode(showTotal, forKey: .showTotal)
        try container.encode(menuBucketIds, forKey: .menuBucketIds)
        try container.encode(showDevices, forKey: .showDevices)
        try container.encode(deviceLabels, forKey: .deviceLabels)
        try container.encode(openAtLogin, forKey: .openAtLogin)
        try container.encode(ssh, forKey: .ssh)
    }

    /// Defaults stay in front. Custom buckets keep the order they were saved in.
    public static func mergingDefaults(_ stored: [Bucket]) -> [Bucket] {
        var defaultNames: [String: String] = [:]
        for bucket in stored where Bucket.defaults.contains(where: { $0.id == bucket.id }) {
            let name = bucket.name.trimmingCharacters(in: .whitespacesAndNewlines)
            if !name.isEmpty { defaultNames[bucket.id] = name }
        }
        let defaults = Bucket.defaults.map { bucket in
            Bucket(id: bucket.id, name: defaultNames[bucket.id] ?? bucket.name)
        }
        var extras: [Bucket] = []
        var seen = Set(defaults.map(\.id))
        for bucket in stored {
            let name = bucket.name.trimmingCharacters(in: .whitespacesAndNewlines)
            guard Bucket.isValidId(bucket.id), !name.isEmpty, seen.insert(bucket.id).inserted else { continue }
            extras.append(Bucket(id: bucket.id, name: name))
        }
        return defaults + extras
    }
}

public enum SSHTarget {
    public static func isValid(_ value: String) -> Bool {
        let parts = value.split(separator: "@", omittingEmptySubsequences: false)
        guard parts.count == 2 else { return false }
        func ok(_ text: Substring, max: Int) -> Bool {
            !text.isEmpty && text.count <= max && text.allSatisfy { character in
                character.isLetter || character.isNumber || character == "." || character == "_" || character == "-"
            }
        }
        return ok(parts[0], max: 64) && ok(parts[1], max: 128)
    }
}

public struct BucketEffect: Equatable, Sendable {
    public var id: String
    public var marked: Bool

    public init(id: String, marked: Bool) {
        self.id = id
        self.marked = marked
    }
}

public func bucketId(from name: String, existing: [Bucket]) -> String? {
    let base = Bucket.slug(name)
    guard Bucket.isValidId(base) else { return nil }
    let taken = Set(existing.map(\.id))
    if !taken.contains(base) { return base }
    for number in 2...50 {
        let candidate = String("\(base)-\(number)".prefix(32))
        if Bucket.isValidId(candidate), !taken.contains(candidate) { return candidate }
    }
    return nil
}

public func effectiveBucket(_ category: String, preferences: Preferences) -> BucketEffect {
    let buckets = Preferences.mergingDefaults(preferences.buckets)
    let known = Set(buckets.map(\.id)).union([Bucket.other.id])
    if let assigned = preferences.assignments[category], known.contains(assigned) {
        return BucketEffect(id: assigned, marked: true)
    }
    if let builtin = defaultBucket(for: category), known.contains(builtin) {
        return BucketEffect(id: builtin, marked: false)
    }
    return BucketEffect(id: Bucket.other.id, marked: false)
}

/// Built-in groups. A row the user has marked wins over these.
///
/// Sites and desktop browsers start as work: YouTube, JioHotstar, Netflix, and
/// the other named media categories are already split out, so what is left is
/// the terminal, Teams, research, and other websites. Brave on the phone has
/// no URLs, so `app:brave` stays unmarked instead of being called work.
public func defaultBucket(for category: String) -> String? {
    if let exact = exactCategories[category] { return exact }
    if category.hasPrefix("app:") {
        let slug = String(category.dropFirst(4))
        if workApps.contains(slug) { return "work" }
        if communicationApps.contains(slug) { return "communication" }
        if gamingApps.contains(slug) || gamingStems.contains(where: { slug.contains($0) }) {
            return "gaming"
        }
        return nil
    }
    if category.hasPrefix("site:") { return "work" }
    return nil
}

private let exactCategories: [String: String] = [
    "youtube": "entertainment",
    "hotstar": "entertainment",
    "netflix": "entertainment",
    "primevideo": "entertainment",
    "disney": "entertainment",
    "twitch": "entertainment",
    "tiktok": "entertainment",
    "spotify": "entertainment",
    "video": "entertainment",
    "instagram": "entertainment",
    "twitter": "entertainment",
    "facebook": "entertainment",
    "reddit": "entertainment",
    "whatsapp": "communication",
    "discord": "communication",
    "github": "work",
    "chatgpt": "work",
    "gmail": "work",
]

private let workApps: Set<String> = [
    "ghostty", "terminal", "iterm2", "iterm", "warp", "alacritty", "kitty", "wezterm", "hyper",
    "sublime-text", "code", "visual-studio-code", "cursor", "xcode", "nova", "bbedit",
    "pycharm", "intellij-idea", "idea", "webstorm", "goland", "zed", "vim", "macvim", "emacs",
    "slack", "microsoft-teams", "teams", "zoom", "zoom-us",
    "microsoft-outlook", "outlook", "mail", "spark",
    "notion", "obsidian", "figma", "linear", "tableplus", "postman", "docker",
    "ticktick",
    "zen", "firefox", "safari", "chrome", "chromium", "arc", "edge", "microsoft-edge",
    "orion", "vivaldi", "opera",
]

private let communicationApps: Set<String> = [
    "messages", "telegram", "signal", "messenger",
]

private let gamingApps: Set<String> = [
    "brawl-stars", "clash-royale", "steam", "steam-helper", "epic-games",
    "minecraft", "roblox", "league-of-legends", "valorant", "riot-client",
    "genshin-impact", "battle-net", "battlenet",
]

private let gamingStems = [
    "brawl", "clash-royale", "steam", "minecraft", "roblox",
    "valorant", "genshin", "riot", "battlenet", "battle-net",
]
