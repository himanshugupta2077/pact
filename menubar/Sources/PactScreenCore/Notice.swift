import Foundation

public enum ScreenTimeBand: String, Equatable, Sendable {
    case green
    case yellow
    case red
}

public struct NoticeDevice: Equatable, Sendable {
    public var name: String
    public var seconds: Double

    public init(name: String, seconds: Double) {
        self.name = name
        self.seconds = seconds
    }
}

public struct ScreenTimeNotice: Equatable, Sendable {
    public var title: String
    public var body: String
    public var discord: String

    public init(title: String, body: String, discord: String) {
        self.title = title
        self.body = body
        self.discord = discord
    }
}

public struct NoticeMark: Codable, Equatable, Sendable {
    public var day: String
    public var hour: Int

    public init(day: String, hour: Int) {
        self.day = day
        self.hour = hour
    }
}

/// Under 3 hours is green, 3 through 5 is yellow, and past 5 is red.
public func screenTimeBand(seconds: Double) -> ScreenTimeBand {
    let hours = seconds.isFinite ? seconds / 3600 : 0
    if hours < 3 { return .green }
    if hours <= 5 { return .yellow }
    return .red
}

/// Whole hour reached, starting at 1. Nil below one hour.
public func screenTimeMilestoneHour(seconds: Double) -> Int? {
    guard seconds.isFinite else { return nil }
    let hour = Int((seconds / 3600).rounded(.down))
    return hour >= 1 ? hour : nil
}

public func shouldSendScreenTimeNotice(day: String, hour: Int, previous: NoticeMark?) -> Bool {
    guard hour >= 1, !day.isEmpty else { return false }
    guard let previous, previous.day == day else { return true }
    return hour > previous.hour
}

public func spokenDuration(_ seconds: Double) -> String {
    spokenDuration(minutes: Int((seconds / 60).rounded()))
}

public func spokenDuration(minutes: Int) -> String {
    let minutes = max(0, minutes)
    if minutes <= 0 { return "0 minutes" }
    let hours = minutes / 60
    let mins = minutes % 60
    switch (hours, mins) {
    case (0, 1): return "1 minute"
    case (0, _): return "\(mins) minutes"
    case (1, 0): return "1 hour"
    case (_, 0): return "\(hours) hours"
    case (1, 1): return "1 hour 1 minute"
    case (1, _): return "1 hour \(mins) minutes"
    case (_, 1): return "\(hours) hours 1 minute"
    default: return "\(hours) hours \(mins) minutes"
    }
}

public func noticeDeviceName(_ name: String, override: String?) -> String {
    let custom = override?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    if !custom.isEmpty { return custom }
    let lowered = name.lowercased()
    if lowered.contains("macbook") { return "your MacBook" }
    if lowered.contains("nothing") { return "your Nothing Phone" }
    if lowered.contains("iphone") { return "your iPhone" }
    if lowered.contains("phone") { return "your phone" }
    if lowered.contains("ipad") { return "your iPad" }
    if lowered.contains("laptop") { return "your laptop" }
    let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
    return trimmed.isEmpty ? "a device" : trimmed
}

public func screenTimeWarning(seconds: Double) -> String {
    switch screenTimeBand(seconds: seconds) {
    case .green:
        return "Slow down a bit. Use the screen for necessary stuff, not for passing time."
    case .yellow:
        return "Slow down. You're well into the day. Keep the screen for necessary stuff, not for passing time."
    case .red:
        return "That's enough. Slow down, stop the time-pass, and only use the screen for necessary stuff."
    }
}

public func screenTimeNotice(totalSeconds: Double, devices: [NoticeDevice]) -> ScreenTimeNotice {
    let active = devices.filter { $0.seconds >= 60 }
    let minutes = minuteParts(totalSeconds: totalSeconds, devices: active)
    let title = "Hey, you've used \(spokenDuration(minutes: minutes.total))"
    let warning = screenTimeWarning(seconds: totalSeconds)
    let split = deviceSplit(names: active.map(\.name), minutes: minutes.devices, totalSeconds: totalSeconds, listedSeconds: active.reduce(0) { $0 + $1.seconds })
    let body: String
    let discord: String
    if let split {
        body = "\(split). \(warning)"
        discord = "\(title) of screen time today, \(split). \(warning)"
    } else {
        body = warning
        discord = "\(title) of screen time today. \(warning)"
    }
    return ScreenTimeNotice(title: title, body: body, discord: discord)
}

private func minuteParts(totalSeconds: Double, devices: [NoticeDevice]) -> (total: Int, devices: [Int]) {
    let total = max(0, Int((totalSeconds / 60).rounded()))
    var parts = devices.map { max(0, Int(($0.seconds / 60).rounded())) }
    let listed = devices.reduce(0.0) { $0 + $1.seconds }
    guard !parts.isEmpty, abs(listed - totalSeconds) < 90 else { return (total, parts) }
    let drift = total - parts.reduce(0, +)
    guard drift != 0, let index = parts.indices.max(by: { parts[$0] < parts[$1] }) else {
        return (total, parts)
    }
    let adjusted = parts[index] + drift
    if adjusted >= 0 { parts[index] = adjusted }
    return (total, parts)
}

private func deviceSplit(names: [String], minutes: [Int], totalSeconds: Double, listedSeconds: Double) -> String? {
    guard !names.isEmpty, names.count == minutes.count else { return nil }
    if names.count == 1, abs(listedSeconds - totalSeconds) < 90 {
        return "all on \(names[0])"
    }
    let parts = zip(names, minutes).map { name, mins in
        "\(spokenDuration(minutes: mins)) on \(name)"
    }
    switch parts.count {
    case 1:
        return parts[0]
    case 2:
        return "\(parts[0]) and \(parts[1])"
    default:
        return parts.dropLast().joined(separator: ", ") + ", and " + parts[parts.count - 1]
    }
}
