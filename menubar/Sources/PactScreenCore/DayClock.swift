import Foundation

/// Screen-time days match the collectors. The zone is site.json when set, otherwise this Mac's zone.
public enum DayClock {
    public static let timeZone: TimeZone = SiteFile.load().timeZone ?? .current

    public static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        return calendar
    }

    public static func date(from day: String) -> Date? {
        let parts = day.split(separator: "-")
        guard parts.count == 3,
              parts[0].count == 4,
              parts[1].count == 2,
              parts[2].count == 2,
              let year = Int(parts[0]),
              let month = Int(parts[1]),
              let dayNumber = Int(parts[2])
        else { return nil }
        var components = DateComponents()
        components.calendar = calendar
        components.timeZone = timeZone
        components.year = year
        components.month = month
        components.day = dayNumber
        guard let date = components.date else { return nil }
        let roundTrip = calendar.dateComponents([.year, .month, .day], from: date)
        guard roundTrip.year == year, roundTrip.month == month, roundTrip.day == dayNumber else {
            return nil
        }
        return date
    }

    public static func string(from date: Date) -> String {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    public static func today(now: Date = Date()) -> String {
        string(from: now)
    }

    /// Move a day, but never past `today`. YYYY-MM-DD sorts in calendar order.
    public static func shift(_ day: String, by days: Int, today todayString: String? = nil) -> String {
        let cap = todayString ?? today()
        guard let date = date(from: day),
              let next = calendar.date(byAdding: .day, value: days, to: date)
        else { return day }
        let value = string(from: next)
        if days > 0, value > cap { return cap }
        return value
    }

    /// Monday through Sunday, in `timeZone`, for the week that contains `day`.
    public static func weekDays(containing day: String) -> [String] {
        guard let date = date(from: day) else { return day.isEmpty ? [] : [day] }
        let weekday = calendar.component(.weekday, from: date)
        let fromMonday = (weekday + 5) % 7
        guard let monday = calendar.date(byAdding: .day, value: -fromMonday, to: date) else {
            return [day]
        }
        let days = (0..<7).compactMap { offset in
            calendar.date(byAdding: .day, value: offset, to: monday).map { string(from: $0) }
        }
        return days.isEmpty ? [day] : days
    }
}
