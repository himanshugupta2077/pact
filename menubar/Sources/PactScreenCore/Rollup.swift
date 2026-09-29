import Foundation

public struct BucketTotal: Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var seconds: Double

    public init(id: String, name: String, seconds: Double) {
        self.id = id
        self.name = name
        self.seconds = seconds
    }

    public var menuShort: String {
        Bucket(id: id, name: name).menuShort
    }
}

public struct DeviceTotal: Equatable, Sendable, Identifiable {
    public var deviceId: String
    public var name: String
    public var platform: String
    public var seconds: Double
    public var buckets: [BucketTotal]

    public var id: String { deviceId.isEmpty ? name : deviceId }

    public init(deviceId: String, name: String, platform: String = "", seconds: Double, buckets: [BucketTotal]) {
        self.deviceId = deviceId
        self.name = name
        self.platform = platform
        self.seconds = seconds
        self.buckets = buckets
    }
}

public struct AppTotal: Equatable, Sendable, Identifiable {
    public var name: String
    public var seconds: Double

    public var id: String { name }

    public init(name: String, seconds: Double) {
        self.name = name
        self.seconds = seconds
    }
}

public struct HourTotal: Equatable, Sendable, Identifiable {
    public var hour: Int
    public var buckets: [BucketTotal]

    public var id: Int { hour }
    public var seconds: Double { buckets.reduce(0) { $0 + $1.seconds } }

    public init(hour: Int, buckets: [BucketTotal]) {
        self.hour = hour
        self.buckets = buckets
    }
}

public struct CategoryRow: Equatable, Sendable, Identifiable {
    public var category: String
    public var name: String
    public var seconds: Double
    public var bucketId: String
    public var marked: Bool

    public var id: String { category }

    public init(category: String, name: String, seconds: Double, bucketId: String, marked: Bool) {
        self.category = category
        self.name = name
        self.seconds = seconds
        self.bucketId = bucketId
        self.marked = marked
    }
}

public struct CategoryGroup: Equatable, Sendable {
    /// Set for the URLs group. A named app or category has no title and one row.
    public var title: String?
    public var rows: [CategoryRow]

    public var seconds: Double { rows.reduce(0) { $0 + $1.seconds } }

    public init(title: String?, rows: [CategoryRow]) {
        self.title = title
        self.rows = rows
    }
}

public struct ClassTotal: Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var seconds: Double
    public var buckets: [BucketTotal]

    public init(id: String, name: String, seconds: Double, buckets: [BucketTotal]) {
        self.id = id
        self.name = name
        self.seconds = seconds
        self.buckets = buckets
    }
}

public struct UsageSlice: Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var seconds: Double
    public var weekSeconds: Double

    public init(id: String, name: String, seconds: Double, weekSeconds: Double) {
        self.id = id
        self.name = name
        self.seconds = seconds
        self.weekSeconds = weekSeconds
    }
}

public struct DayRollup: Equatable, Sendable {
    public var day: String
    public var screenSeconds: Double
    public var buckets: [BucketTotal]
    public var classes: [ClassTotal]
    public var devices: [DeviceTotal]
    public var apps: [AppTotal]
    public var hours: [HourTotal]
    public var categories: [CategoryRow]

    public init(
        day: String,
        screenSeconds: Double,
        buckets: [BucketTotal],
        classes: [ClassTotal],
        devices: [DeviceTotal],
        apps: [AppTotal],
        hours: [HourTotal],
        categories: [CategoryRow]
    ) {
        self.day = day
        self.screenSeconds = screenSeconds
        self.buckets = buckets
        self.classes = classes
        self.devices = devices
        self.apps = apps
        self.hours = hours
        self.categories = categories
    }
}

public func rollup(_ payload: DayPayload, preferences: Preferences) -> DayRollup {
    let buckets = Preferences.cleanedBuckets(preferences.buckets)
    var prefs = preferences
    prefs.buckets = buckets
    let totalsBuckets = [Bucket.work] + buckets

    var categorySeconds: [String: Double] = [:]
    var appSeconds: [String: Double] = [:]
    var hourCategory: [Int: [String: Double]] = [:]
    var deviceRows: [DeviceTotal] = []

    for device in payload.devices {
        var perBucket: [String: Double] = [:]
        for row in device.byCategory where row.seconds > 0 {
            categorySeconds[row.category, default: 0] += row.seconds
            perBucket[effectiveBucket(row.category, preferences: prefs).id, default: 0] += row.seconds
        }
        for row in device.byApp where row.seconds > 0 {
            appSeconds[row.app, default: 0] += row.seconds
        }
        for hour in device.hours where (0..<24).contains(hour.hour) {
            for (category, seconds) in hour.categories where seconds > 0 {
                hourCategory[hour.hour, default: [:]][category, default: 0] += seconds
            }
        }
        let name = device.deviceName.isEmpty ? (device.hostname.isEmpty ? "Device" : device.hostname) : device.deviceName
        deviceRows.append(
            DeviceTotal(
                deviceId: device.deviceId,
                name: name,
                platform: device.platform,
                seconds: device.screenSeconds,
                buckets: bucketTotals(perBucket, buckets: totalsBuckets)
            )
        )
    }
    deviceRows.sort { lhs, rhs in
        if lhs.seconds == rhs.seconds { return lhs.name < rhs.name }
        return lhs.seconds > rhs.seconds
    }

    let byId = Dictionary(uniqueKeysWithValues: buckets.map { ($0.id, $0) })
    var bucketSeconds: [String: Double] = [:]
    var directWork = 0.0
    var loosePersonal = 0.0
    var categories: [CategoryRow] = []
    for (category, seconds) in categorySeconds where seconds > 0 {
        let effect = effectiveBucket(category, preferences: prefs)
        if byId[effect.id] != nil {
            bucketSeconds[effect.id, default: 0] += seconds
        } else if effect.id == Bucket.work.id {
            directWork += seconds
        } else {
            loosePersonal += seconds
        }
        categories.append(
            CategoryRow(
                category: category,
                name: displayName(for: category),
                seconds: seconds,
                bucketId: effect.id,
                marked: effect.marked
            )
        )
    }
    categories.sort { lhs, rhs in
        if lhs.seconds == rhs.seconds { return lhs.name < rhs.name }
        return lhs.seconds > rhs.seconds
    }

    let hours = (0..<24).map { hour -> HourTotal in
        var perBucket: [String: Double] = [:]
        for (category, seconds) in hourCategory[hour] ?? [:] {
            perBucket[effectiveBucket(category, preferences: prefs).id, default: 0] += seconds
        }
        return HourTotal(hour: hour, buckets: bucketTotals(perBucket, buckets: totalsBuckets))
    }

    let apps = appSeconds.map { AppTotal(name: $0.key, seconds: $0.value) }.sorted { lhs, rhs in
        if lhs.seconds == rhs.seconds { return lhs.name < rhs.name }
        return lhs.seconds > rhs.seconds
    }

    func childTotals(parent: String) -> [BucketTotal] {
        buckets.compactMap { bucket in
            guard bucket.parent == parent, let seconds = bucketSeconds[bucket.id], seconds > 0 else { return nil }
            return BucketTotal(id: bucket.id, name: bucket.name, seconds: seconds)
        }
    }
    var workChildren = childTotals(parent: Bucket.work.id)
    var personalChildren = childTotals(parent: Bucket.personal.id)
    if loosePersonal > 0 {
        personalChildren.append(BucketTotal(id: Bucket.unassigned.id, name: Bucket.unassigned.name, seconds: loosePersonal))
    }
    workChildren.sort(by: moreTime)
    personalChildren.sort(by: moreTime)
    let workSeconds = directWork + workChildren.reduce(0) { $0 + $1.seconds }
    let personalSeconds = personalChildren.reduce(0) { $0 + $1.seconds }
    var flat: [BucketTotal] = []
    if workSeconds > 0 {
        flat.append(BucketTotal(id: Bucket.work.id, name: Bucket.work.name, seconds: workSeconds))
    }
    flat.append(contentsOf: (workChildren + personalChildren.filter { $0.id != Bucket.unassigned.id }).sorted(by: moreTime))
    if loosePersonal > 0 {
        flat.append(BucketTotal(id: Bucket.unassigned.id, name: Bucket.unassigned.name, seconds: loosePersonal))
    }
    let classes = [
        ClassTotal(id: Bucket.work.id, name: Bucket.work.name, seconds: workSeconds, buckets: workChildren),
        ClassTotal(id: Bucket.personal.id, name: Bucket.personal.name, seconds: personalSeconds, buckets: personalChildren),
    ]

    return DayRollup(
        day: payload.day,
        screenSeconds: payload.devices.reduce(0) { $0 + $1.screenSeconds },
        buckets: flat,
        classes: classes,
        devices: deviceRows,
        apps: apps,
        hours: hours,
        categories: categories
    )
}

private func moreTime(_ lhs: BucketTotal, _ rhs: BucketTotal) -> Bool {
    if lhs.seconds == rhs.seconds { return lhs.name < rhs.name }
    return lhs.seconds > rhs.seconds
}

func bucketTotals(_ seconds: [String: Double], buckets: [Bucket]) -> [BucketTotal] {
    var rows: [BucketTotal] = []
    var seen = Set<String>()
    for bucket in buckets {
        seen.insert(bucket.id)
        let value = seconds[bucket.id] ?? 0
        if value > 0 {
            rows.append(BucketTotal(id: bucket.id, name: bucket.name, seconds: value))
        }
    }
    for (id, value) in seconds.sorted(by: { $0.key < $1.key }) where value > 0 && !seen.contains(id) {
        let name = id == Bucket.unassigned.id || id == Bucket.personal.id ? Bucket.unassigned.name : id
        rows.append(BucketTotal(id: id, name: name, seconds: value))
    }
    return rows
}

/// Folds bucket rows into the two fixed classes for charts.
/// `workBucketIds` are buckets the user put under Work. The Work class itself counts too.
public func classParts(_ buckets: [BucketTotal], workBucketIds: Set<String> = []) -> [BucketTotal] {
    let workIds = workBucketIds.union([Bucket.work.id])
    let work = buckets.filter { workIds.contains($0.id) }.reduce(0) { $0 + $1.seconds }
    let personal = buckets.filter { !workIds.contains($0.id) }.reduce(0) { $0 + $1.seconds }
    var parts: [BucketTotal] = []
    if work > 0 {
        parts.append(BucketTotal(id: Bucket.work.id, name: Bucket.work.name, seconds: work))
    }
    if personal > 0 {
        parts.append(BucketTotal(id: Bucket.personal.id, name: Bucket.personal.name, seconds: personal))
    }
    return parts
}

/// Named categories stay single rows. Every `site:` row goes in one URLs group,
/// whether its time is under a minute or over.
public func categoryGroups(_ rows: [CategoryRow]) -> [CategoryGroup] {
    var singles: [CategoryRow] = []
    var sites: [CategoryRow] = []
    for row in rows {
        if row.category.hasPrefix("site:") {
            sites.append(row)
        } else {
            singles.append(row)
        }
    }
    sites.sort { lhs, rhs in
        if lhs.seconds == rhs.seconds { return lhs.name < rhs.name }
        return lhs.seconds > rhs.seconds
    }
    var groups = singles.map { CategoryGroup(title: nil, rows: [$0]) }
    if !sites.isEmpty {
        groups.append(CategoryGroup(title: "URLs", rows: sites))
    }
    groups.sort { lhs, rhs in
        if lhs.seconds == rhs.seconds { return lhs.sortName < rhs.sortName }
        return lhs.seconds > rhs.seconds
    }
    return groups
}

private extension CategoryGroup {
    var sortName: String { title ?? rows.first?.name ?? "" }
}

public struct BreakdownItem: Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var seconds: Double
    public var colorId: String
    public var children: [BreakdownItem]

    public init(id: String, name: String, seconds: Double, colorId: String, children: [BreakdownItem] = []) {
        self.id = id
        self.name = name
        self.seconds = seconds
        self.colorId = colorId
        self.children = children
    }
}

/// What makes up one class on this day.
/// Work is the apps and URLs counted as work. Sites stay under one URLs row.
/// Personal is its buckets, each holding the apps and URLs inside that bucket.
public func classConstituents(_ rollup: DayRollup, classId: String) -> [BreakdownItem] {
    if classId == Bucket.work.id {
        let nested = Set(rollup.classes.first { $0.id == Bucket.work.id }?.buckets.map(\.id) ?? [])
        let ids = nested.union([Bucket.work.id])
        let rows = rollup.categories.filter { ids.contains($0.bucketId) && $0.seconds > 0.5 }
        return breakdownItems(rows, scope: Bucket.work.id, scopeColor: Bucket.work.id)
    }
    if classId == Bucket.personal.id {
        let buckets = rollup.classes.first { $0.id == Bucket.personal.id }?.buckets ?? []
        return buckets.filter { $0.seconds > 0.5 }.map { bucket in
            let rows = rollup.categories.filter { row in
                guard row.seconds > 0.5 else { return false }
                if row.bucketId == bucket.id { return true }
                return bucket.id == Bucket.unassigned.id
                    && (row.bucketId == Bucket.personal.id || row.bucketId == Bucket.unassigned.id)
            }
            return BreakdownItem(
                id: bucket.id,
                name: bucket.name,
                seconds: bucket.seconds,
                colorId: bucket.id,
                children: breakdownItems(rows, scope: bucket.id, scopeColor: bucket.id)
            )
        }
    }
    return []
}

private func breakdownItems(_ rows: [CategoryRow], scope: String, scopeColor: String) -> [BreakdownItem] {
    categoryGroups(rows).map { group in
        if let title = group.title {
            let colors = Set(group.rows.map(\.bucketId))
            return BreakdownItem(
                id: "urls:\(scope)",
                name: title,
                seconds: group.seconds,
                colorId: colors.count == 1 ? (colors.first ?? scopeColor) : scopeColor,
                children: group.rows.map(leafItem)
            )
        }
        return leafItem(group.rows[0])
    }
}

private func leafItem(_ row: CategoryRow) -> BreakdownItem {
    BreakdownItem(id: row.category, name: row.name, seconds: row.seconds, colorId: row.bucketId)
}

extension DayRollup {
    public static func empty(day: String) -> DayRollup {
        DayRollup(
            day: day,
            screenSeconds: 0,
            buckets: [],
            classes: [
                ClassTotal(id: Bucket.work.id, name: Bucket.work.name, seconds: 0, buckets: []),
                ClassTotal(id: Bucket.personal.id, name: Bucket.personal.name, seconds: 0, buckets: []),
            ],
            devices: [],
            apps: [],
            hours: [],
            categories: []
        )
    }
}

public func rollupWeek(_ payloads: [DayPayload], day: String, preferences: Preferences) -> DayRollup {
    let allowed = Set(DayClock.weekDays(containing: day))
    let devices = payloads.filter { allowed.contains($0.day) }.flatMap(\.devices)
    return rollup(DayPayload(day: day, devices: devices), preferences: preferences)
}

/// Leaf pieces of the day, largest day total first. Work is not forced to the top.
/// A bucket the user put under Work is its own piece. Time assigned straight to Work,
/// with no work bucket, is one Work piece. Personal is the class total, not a piece.
public func usageSlices(day: DayRollup, week: DayRollup?) -> [UsageSlice] {
    struct Accum {
        var name: String
        var day: Double
        var week: Double
    }
    var map: [String: Accum] = [:]
    func add(id: String, name: String, seconds: Double, weekSide: Bool) {
        guard seconds > 0 else { return }
        var row = map[id] ?? Accum(name: name, day: 0, week: 0)
        row.name = name
        if weekSide {
            row.week += seconds
        } else {
            row.day += seconds
        }
        map[id] = row
    }
    func absorb(_ rollup: DayRollup, weekSide: Bool) {
        for klass in rollup.classes {
            let nested = klass.buckets.reduce(0) { $0 + $1.seconds }
            let direct = klass.seconds - nested
            if direct > 0.5 {
                if klass.id == Bucket.work.id {
                    add(id: Bucket.work.id, name: Bucket.work.name, seconds: direct, weekSide: weekSide)
                } else {
                    add(id: Bucket.unassigned.id, name: Bucket.unassigned.name, seconds: direct, weekSide: weekSide)
                }
            }
            for bucket in klass.buckets {
                add(id: bucket.id, name: bucket.name, seconds: bucket.seconds, weekSide: weekSide)
            }
        }
    }
    absorb(day, weekSide: false)
    if let week {
        absorb(week, weekSide: true)
    }
    return map.map { id, row in
        UsageSlice(id: id, name: row.name, seconds: row.day, weekSeconds: row.week)
    }
    .filter { $0.seconds > 0.5 || $0.weekSeconds > 0.5 }
    .sorted { lhs, rhs in
        if lhs.seconds == rhs.seconds {
            if lhs.weekSeconds == rhs.weekSeconds { return lhs.name < rhs.name }
            return lhs.weekSeconds > rhs.weekSeconds
        }
        return lhs.seconds > rhs.seconds
    }
}
