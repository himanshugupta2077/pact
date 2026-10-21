import Foundation
import PactScreenCore

struct FetchFailure: LocalizedError, Sendable {
    var message: String
    var errorDescription: String? { message }
}

enum Fetcher {
    static func loadScript() throws -> String {
        guard let url = Bundle.main.url(forResource: "fetch_day", withExtension: "py") else {
            throw FetchFailure(message: "Pact Screen is missing its reader. Rebuild the app.")
        }
        return try String(contentsOf: url, encoding: .utf8)
    }

    static func fetch(day: String, ssh: String) async throws -> [DayPayload] {
        let script = try loadScript()
        return try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    continuation.resume(returning: try run(day: day, ssh: ssh, script: script))
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    /// Read the Monday–Sunday week that contains `day`. The remote command only runs fetch_day.py.
    static func run(day: String, ssh: String, script: String) throws -> [DayPayload] {
        guard DayClock.date(from: day) != nil else {
            throw FetchFailure(message: "That day is not one Pact stores.")
        }
        guard SSHTarget.isValid(ssh) else {
            throw FetchFailure(message: "The SSH host is not valid.")
        }
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/ssh")
        process.arguments = [
            "-o", "BatchMode=yes",
            "-o", "ConnectTimeout=10",
            ssh,
            "PACT_ACTIVITY_WEEK=1 PACT_ACTIVITY_DAY=\(day) PACT_ACTIVITY_ROOT=\(SiteFile.activityRoot) python3 -",
        ]
        let stdout = Pipe()
        let stderr = Pipe()
        let stdin = Pipe()
        process.standardOutput = stdout
        process.standardError = stderr
        process.standardInput = stdin
        do {
            try process.run()
        } catch {
            throw FetchFailure(message: "Couldn't start ssh.")
        }
        // python3 on the VPS blocks until stdin closes. Read both pipes while it runs
        // so a large day cannot fill the pipe and stall.
        let capture = PipeCapture()
        let group = DispatchGroup()
        group.enter()
        DispatchQueue.global().async {
            capture.setOut(stdout.fileHandleForReading.readDataToEndOfFile())
            group.leave()
        }
        group.enter()
        DispatchQueue.global().async {
            capture.setErr(stderr.fileHandleForReading.readDataToEndOfFile())
            group.leave()
        }
        DispatchQueue.global().asyncAfter(deadline: .now() + 25) {
            if process.isRunning { process.terminate() }
        }
        let started = Date()
        stdin.fileHandleForWriting.write(Data(script.utf8))
        stdin.fileHandleForWriting.closeFile()
        group.wait()
        process.waitUntilExit()
        let (outData, errData) = capture.values()
        if process.terminationStatus != 0 {
            if Date().timeIntervalSince(started) >= 24 {
                throw FetchFailure(message: "The VPS took too long.")
            }
            let detail = String(data: errData, encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .replacingOccurrences(of: "\n", with: " ") ?? ""
            if detail.isEmpty {
                throw FetchFailure(message: "Couldn't reach \(ssh).")
            }
            throw FetchFailure(message: "Couldn't reach \(ssh). \(String(detail.prefix(180)))")
        }
        guard !outData.isEmpty else {
            throw FetchFailure(message: "The VPS sent something that wasn't screen time.")
        }
        do {
            return try decodeDays(outData)
        } catch {
            throw FetchFailure(message: "The VPS sent something that wasn't screen time.")
        }
    }
}

private struct DayBatch: Decodable {
    var days: [DayPayload]
}

private func decodeDays(_ data: Data) throws -> [DayPayload] {
    let decoder = JSONDecoder()
    decoder.keyDecodingStrategy = .convertFromSnakeCase
    if let batch = try? decoder.decode(DayBatch.self, from: data) {
        return batch.days
    }
    return [try DayPayload.decode(data)]
}

private final class PipeCapture: @unchecked Sendable {
    private let lock = NSLock()
    private var out = Data()
    private var err = Data()

    func setOut(_ data: Data) {
        lock.lock()
        out = data
        lock.unlock()
    }

    func setErr(_ data: Data) {
        lock.lock()
        err = data
        lock.unlock()
    }

    func values() -> (Data, Data) {
        lock.lock()
        defer { lock.unlock() }
        return (out, err)
    }
}
