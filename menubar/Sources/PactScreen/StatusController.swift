import AppKit

@MainActor
final class StatusController: NSObject, NSPopoverDelegate {
    private let statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let popover = NSPopover()
    private let model: AppModel
    /// A click that closes the popover also delivers the button action. Ignore that one.
    private var ignoreClickUntil: Date?

    init(model: AppModel) {
        self.model = model
        super.init()
        let panel = PanelController(model: model)
        popover.contentViewController = panel
        popover.behavior = .transient
        popover.delegate = self
        popover.contentSize = NSSize(width: 400, height: 640)
        if let button = statusItem.button {
            let image = NSImage(systemSymbolName: "hourglass", accessibilityDescription: "Screen time")
            image?.isTemplate = true
            button.image = image
            button.imagePosition = .imageLeading
            button.font = .monospacedDigitSystemFont(ofSize: 13, weight: .medium)
            button.target = self
            button.action = #selector(click)
        }
        applyTitle(model.menuTitle)
        model.onMenuTitle = { [weak self] title in
            self?.applyTitle(title)
        }
    }

    func applyTitle(_ title: String) {
        statusItem.button?.title = " " + title
        statusItem.button?.setAccessibilityLabel("Screen time \(title)")
        statusItem.button?.toolTip = "Pact screen time"
    }

    @objc private func click() {
        if let ignoreClickUntil, Date() < ignoreClickUntil {
            self.ignoreClickUntil = nil
            return
        }
        if popover.isShown {
            popover.performClose(nil)
            return
        }
        guard let button = statusItem.button else { return }
        NSApp.activate()
        popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
    }

    func popoverWillClose(_ notification: Notification) {
        ignoreClickUntil = Date().addingTimeInterval(0.2)
    }
}
