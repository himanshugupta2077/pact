import Foundation

/// Private host settings written by menubar/build.sh from pact.local.json.
/// The file lives in Application Support and is not part of the repo.
public enum SiteFile {
    public struct Values: Equatable, Sendable {
        public var ssh: String
        public var activityRoot: String
        public var timeZone: TimeZone?

        public init(ssh: String, activityRoot: String, timeZone: TimeZone?) {
            self.ssh = ssh
            self.activityRoot = activityRoot
            self.timeZone = timeZone
        }
    }

    public static let fallbackSSH = "pact@pact-host"
    public static let fallbackActivityRoot = "/var/lib/pact/activity"

    public static func load() -> Values {
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("PactScreen/site.json")
        guard let data = try? Data(contentsOf: url),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else {
            return Values(ssh: "", activityRoot: "", timeZone: nil)
        }
        let ssh = (object["ssh"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let root = (object["activityRoot"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let zoneName = (object["timezone"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return Values(
            ssh: SSHTarget.isValid(ssh) ? ssh : "",
            activityRoot: safeActivityRoot(root) ?? "",
            timeZone: zoneName.isEmpty ? nil : TimeZone(identifier: zoneName)
        )
    }

    public static var activityRoot: String {
        let value = load().activityRoot
        return value.isEmpty ? fallbackActivityRoot : value
    }

    /// A remote path used inside an SSH command. Letters, digits, and /._- only.
    public static func safeActivityRoot(_ value: String) -> String? {
        guard !value.isEmpty, value.count <= 200, value.hasPrefix("/"), !value.contains("..") else {
            return nil
        }
        let ok = value.allSatisfy { character in
            character.isLetter || character.isNumber || character == "/" || character == "."
                || character == "_" || character == "-"
        }
        return ok ? value : nil
    }
}
