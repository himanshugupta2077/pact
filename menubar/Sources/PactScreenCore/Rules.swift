import Foundation

public struct Bucket: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    /// `work` or `personal`. Older saves omit this and stay under Personal.
    public var parent: String

    public init(id: String, name: String, parent: String = "personal") {
        self.id = id
        self.name = name
        self.parent = parent == "work" ? "work" : "personal"
    }

    private enum CodingKeys: String, CodingKey {
        case id, name, parent
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let id = try container.decode(String.self, forKey: .id)
        let name = try container.decode(String.self, forKey: .name)
        let parent = try container.decodeIfPresent(String.self, forKey: .parent) ?? Bucket.personal.id
        self.init(id: id, name: name, parent: parent)
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(name, forKey: .name)
        try container.encode(parent, forKey: .parent)
    }

    /// Fixed classes. They are not buckets: they cannot be renamed or removed.
    public static let work = Bucket(id: "work", name: "Work")
    public static let personal = Bucket(id: "personal", name: "Personal")
    /// Shown when time is Personal and no kept bucket claims it.
    public static let unassigned = Bucket(id: "unassigned", name: "Unassigned")
    public static let reservedIds: Set<String> = [work.id, personal.id, unassigned.id]

    public static let defaults: [Bucket] = [
        Bucket(id: "entertainment", name: "Entertainment"),
        Bucket(id: "gaming", name: "Gaming"),
        Bucket(id: "communication", name: "Communication"),
        other,
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
        guard !reservedIds.contains(id), let first = id.first, first.isLetter, id.count <= 32 else { return false }
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
        ssh: SiteFile.fallbackSSH
    )

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        assignments = try container.decodeIfPresent([String: String].self, forKey: .assignments) ?? [:]
        if container.contains(.buckets) {
            let stored = try container.decode([Bucket].self, forKey: .buckets)
            buckets = Preferences.migrateBuckets(stored)
        } else {
            buckets = Bucket.defaults
        }
        showTotal = try container.decodeIfPresent(Bool.self, forKey: .showTotal) ?? true
        menuBucketIds = try container.decodeIfPresent([String].self, forKey: .menuBucketIds) ?? []
        showDevices = try container.decodeIfPresent(Bool.self, forKey: .showDevices) ?? false
        deviceLabels = try container.decodeIfPresent([String: String].self, forKey: .deviceLabels) ?? [:]
        openAtLogin = try container.decodeIfPresent(Bool.self, forKey: .openAtLogin) ?? true
        let host = try container.decodeIfPresent(String.self, forKey: .ssh) ?? SiteFile.fallbackSSH
        ssh = SSHTarget.isValid(host) ? host : SiteFile.fallbackSSH
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

    /// Drops blank names, reserved class ids, and duplicates. Order stays as saved.
    public static func cleanedBuckets(_ stored: [Bucket]) -> [Bucket] {
        var result: [Bucket] = []
        var seen = Set<String>()
        for bucket in stored {
            let name = bucket.name.trimmingCharacters(in: .whitespacesAndNewlines)
            guard Bucket.isValidId(bucket.id), !name.isEmpty, seen.insert(bucket.id).inserted else { continue }
            result.append(Bucket(id: bucket.id, name: name, parent: bucket.parent))
        }
        return result
    }

    /// Older saves listed Work as a bucket and left Other implied.
    /// A list that still contains Work is that older shape.
    public static func migrateBuckets(_ stored: [Bucket]) -> [Bucket] {
        let legacy = stored.contains { $0.id == Bucket.work.id }
        var cleaned = cleanedBuckets(stored)
        if legacy, !cleaned.contains(where: { $0.id == Bucket.other.id }) {
            cleaned.append(Bucket.other)
        }
        return cleaned
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
    guard !base.isEmpty else { return nil }
    let taken = Set(existing.map(\.id)).union(Bucket.reservedIds)
    if Bucket.isValidId(base), !taken.contains(base) { return base }
    let reserved = Bucket.reservedIds.contains(base)
    guard Bucket.isValidId(base) || reserved else { return nil }
    for number in 2...50 {
        let candidate = String("\(base)-\(number)".prefix(32))
        if Bucket.isValidId(candidate), !taken.contains(candidate) { return candidate }
    }
    return nil
}

public func effectiveBucket(_ category: String, preferences: Preferences) -> BucketEffect {
    let live = Set(Preferences.cleanedBuckets(preferences.buckets).map(\.id))
    if let assigned = preferences.assignments[category] {
        if assigned == Bucket.work.id {
            return BucketEffect(id: Bucket.work.id, marked: true)
        }
        if assigned == Bucket.personal.id {
            return BucketEffect(id: Bucket.personal.id, marked: true)
        }
        if live.contains(assigned) {
            return BucketEffect(id: assigned, marked: true)
        }
    }
    if let builtin = defaultBucket(for: category) {
        if builtin == Bucket.work.id {
            return BucketEffect(id: Bucket.work.id, marked: false)
        }
        if live.contains(builtin) {
            return BucketEffect(id: builtin, marked: false)
        }
    }
    if live.contains(Bucket.other.id) {
        return BucketEffect(id: Bucket.other.id, marked: false)
    }
    return BucketEffect(id: Bucket.unassigned.id, marked: false)
}

/// Built-in groups. A row the user has marked wins over these.
///
/// Sites and desktop browsers start as work. YouTube, JioHotstar, Netflix, and
/// the other named media categories are already split out. Brave on the phone
/// has no URLs, so `app:brave` stays unmarked instead of being called work.
/// A bucket placed under Work counts as Work. Personal, and every other bucket, counts as Personal.
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
