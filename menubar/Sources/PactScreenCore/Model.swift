import Foundation

public struct CategorySeconds: Codable, Equatable, Sendable {
    public var category: String
    public var seconds: Double

    public init(category: String, seconds: Double) {
        self.category = category
        self.seconds = seconds
    }
}

public struct AppSeconds: Codable, Equatable, Sendable {
    public var app: String
    public var seconds: Double

    public init(app: String, seconds: Double) {
        self.app = app
        self.seconds = seconds
    }
}

public struct HourBucket: Codable, Equatable, Sendable {
    public var hour: Int
    public var categories: [String: Double]

    public init(hour: Int, categories: [String: Double]) {
        self.hour = hour
        self.categories = categories
    }
}

public struct DeviceDay: Codable, Equatable, Sendable {
    public var deviceId: String
    public var deviceName: String
    public var hostname: String
    public var platform: String
    public var screenSeconds: Double
    public var engagedSeconds: Double
    public var gatheredAt: String?
    public var byCategory: [CategorySeconds]
    public var byApp: [AppSeconds]
    public var hours: [HourBucket]

    public init(
        deviceId: String,
        deviceName: String,
        hostname: String,
        platform: String,
        screenSeconds: Double,
        engagedSeconds: Double,
        gatheredAt: String? = nil,
        byCategory: [CategorySeconds],
        byApp: [AppSeconds],
        hours: [HourBucket] = []
    ) {
        self.deviceId = deviceId
        self.deviceName = deviceName
        self.hostname = hostname
        self.platform = platform
        self.screenSeconds = screenSeconds
        self.engagedSeconds = engagedSeconds
        self.gatheredAt = gatheredAt
        self.byCategory = byCategory
        self.byApp = byApp
        self.hours = hours
    }

    private enum CodingKeys: String, CodingKey {
        case deviceId, deviceName, hostname, platform
        case screenSeconds, engagedSeconds, gatheredAt
        case byCategory, byApp, hours
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        deviceId = try container.decodeIfPresent(String.self, forKey: .deviceId) ?? ""
        deviceName = try container.decodeIfPresent(String.self, forKey: .deviceName) ?? ""
        hostname = try container.decodeIfPresent(String.self, forKey: .hostname) ?? ""
        platform = try container.decodeIfPresent(String.self, forKey: .platform) ?? ""
        screenSeconds = try container.decodeIfPresent(Double.self, forKey: .screenSeconds) ?? 0
        engagedSeconds = try container.decodeIfPresent(Double.self, forKey: .engagedSeconds) ?? 0
        gatheredAt = try container.decodeIfPresent(String.self, forKey: .gatheredAt)
        byCategory = try container.decodeIfPresent([CategorySeconds].self, forKey: .byCategory) ?? []
        byApp = try container.decodeIfPresent([AppSeconds].self, forKey: .byApp) ?? []
        hours = try container.decodeIfPresent([HourBucket].self, forKey: .hours) ?? []
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(deviceId, forKey: .deviceId)
        try container.encode(deviceName, forKey: .deviceName)
        try container.encode(hostname, forKey: .hostname)
        try container.encode(platform, forKey: .platform)
        try container.encode(screenSeconds, forKey: .screenSeconds)
        try container.encode(engagedSeconds, forKey: .engagedSeconds)
        try container.encodeIfPresent(gatheredAt, forKey: .gatheredAt)
        try container.encode(byCategory, forKey: .byCategory)
        try container.encode(byApp, forKey: .byApp)
        try container.encode(hours, forKey: .hours)
    }
}

public struct DayPayload: Codable, Equatable, Sendable {
    public var day: String
    public var devices: [DeviceDay]

    public init(day: String, devices: [DeviceDay]) {
        self.day = day
        self.devices = devices
    }

    private enum CodingKeys: String, CodingKey {
        case day, devices
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        day = try container.decodeIfPresent(String.self, forKey: .day) ?? ""
        devices = try container.decodeIfPresent([DeviceDay].self, forKey: .devices) ?? []
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(day, forKey: .day)
        try container.encode(devices, forKey: .devices)
    }

    public static func decode(_ data: Data) throws -> DayPayload {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(DayPayload.self, from: data)
    }

    public func encoded() throws -> Data {
        let encoder = JSONEncoder()
        encoder.keyEncodingStrategy = .convertToSnakeCase
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try encoder.encode(self)
    }
}
