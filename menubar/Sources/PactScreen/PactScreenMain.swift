import AppKit
import PactScreenCore

@main
enum PactScreenMain {
    static func main() {
        let app = NSApplication.shared
        let delegate = AppDelegate()
        app.delegate = delegate
        app.run()
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    private var model: AppModel?
    private var status: StatusController?

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        if CommandLine.arguments.contains("--dump") {
            Dump.run(render: CommandLine.arguments.contains("--render"))
            return
        }
        let model = AppModel()
        self.model = model
        status = StatusController(model: model)
        model.start()
    }
}

enum Dump {
    @MainActor
    static func run(render: Bool) {
        let prefs = (try? Data(contentsOf: Store.rules)).flatMap {
            try? JSONDecoder().decode(Preferences.self, from: $0)
        } ?? .standard
        let day = DayClock.today()
        do {
            let script = try Fetcher.loadScript()
            let payload = try Fetcher.run(day: day, ssh: prefs.ssh, script: script)
            let model = AppModel()
            model.adopt(preferences: prefs, payload: payload, fetchedAt: Date())
            guard let rolled = model.dayRollup else {
                fputs("no rollup\n", stderr)
                exit(1)
            }
            print(model.menuTitle)
            for klass in rolled.classes {
                print("\(klass.name)\t\(formatDuration(klass.seconds))\t\(Int(klass.seconds.rounded()))")
                for bucket in klass.buckets {
                    print("  \(bucket.name)\t\(formatDuration(bucket.seconds))\t\(Int(bucket.seconds.rounded()))")
                }
            }
            for device in rolled.devices {
                print("\(device.name)\t\(formatDuration(device.seconds))\t\(Int(device.seconds.rounded()))")
            }
            print("categories \(rolled.categories.count)")
            print("hours \(rolled.hours.filter { $0.seconds > 0 }.count)")
            if render, let png = renderPNG(model) {
                let url = URL(fileURLWithPath: "/tmp/pact-screen.png")
                try png.write(to: url)
                print("rendered \(url.path)")
            }
            exit(0)
        } catch {
            let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            fputs(message + "\n", stderr)
            exit(1)
        }
    }

    @MainActor
    private static func renderPNG(_ model: AppModel) -> Data? {
        let panel = PanelController(model: model)
        panel.loadView()
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 400, height: 640),
            styleMask: [.borderless],
            backing: .buffered,
            defer: false
        )
        window.appearance = NSAppearance(named: .darkAqua)
        window.contentView = panel.view
        panel.view.frame = NSRect(x: 0, y: 0, width: 400, height: 640)
        panel.view.layoutSubtreeIfNeeded()
        window.layoutIfNeeded()
        window.displayIfNeeded()
        guard let view = window.contentView,
              let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds)
        else { return nil }
        view.cacheDisplay(in: view.bounds, to: rep)
        return rep.representation(using: .png, properties: [:])
    }
}
