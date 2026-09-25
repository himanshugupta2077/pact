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
    public var seconds: Double
    public var buckets: [BucketTotal]

    public var id: String { deviceId.isEmpty ? name : deviceId }

    public init(deviceId: String, name: String, seconds: Double, buckets: [BucketTotal]) {
        self.deviceId = deviceId
        self.name = name
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

public struct DayRollup: Equatable, Sendable {
    public var day: String
    public var screenSeconds: Double
    public var buckets: [BucketTotal]
    public var devices: [DeviceTotal]
    public var apps: [AppTotal]
    public var hours: [HourTotal]
    public var categories: [CategoryRow]

    public init(
        day: String,
        screenSeconds: Double,
        buckets: [BucketTotal],
        devices: [DeviceTotal],
        apps: [AppTotal],
        hours: [HourTotal],
        categories: [CategoryRow]
    ) {
        self.day = day
        self.screenSeconds = screenSeconds
        self.buckets = buckets
        self.devices = devices
        self.apps = apps
        self.hours = hours
        self.categories = categories
    }
}

public func rollup(_ payload: DayPayload, preferences: Preferences) -> DayRollup {
    let buckets = Preferences.mergingDefaults(preferences.buckets)
    var prefs = preferences
    prefs.buckets = buckets

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
                seconds: device.screenSeconds,
                buckets: bucketTotals(perBucket, buckets: buckets)
            )
        )
    }
    deviceRows.sort { lhs, rhs in
        if lhs.seconds == rhs.seconds { return lhs.name < rhs.name }
        return lhs.seconds > rhs.seconds
    }

    var bucketSeconds: [String: Double] = [:]
    var categories: [CategoryRow] = []
    for (category, seconds) in categorySeconds where seconds > 0 {
        let effect = effectiveBucket(category, preferences: prefs)
        bucketSeconds[effect.id, default: 0] += seconds
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
        return HourTotal(hour: hour, buckets: bucketTotals(perBucket, buckets: buckets))
    }

    let apps = appSeconds.map { AppTotal(name: $0.key, seconds: $0.value) }.sorted { lhs, rhs in
        if lhs.seconds == rhs.seconds { return lhs.name < rhs.name }
        return lhs.seconds > rhs.seconds
    }

    return DayRollup(
        day: payload.day,
        screenSeconds: payload.devices.reduce(0) { $0 + $1.screenSeconds },
        buckets: bucketTotals(bucketSeconds, buckets: buckets),
        devices: deviceRows,
        apps: apps,
        hours: hours,
        categories: categories
    )
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
    let other = seconds[Bucket.other.id] ?? 0
    if other > 0 {
        rows.append(BucketTotal(id: Bucket.other.id, name: Bucket.other.name, seconds: other))
    }
    for (id, value) in seconds.sorted(by: { $0.key < $1.key }) where value > 0 && !seen.contains(id) && id != Bucket.other.id {
        rows.append(BucketTotal(id: id, name: id, seconds: value))
    }
    return rows
}
