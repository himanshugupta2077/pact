import AppKit

@MainActor
final class StatusController: NSObject, NSPopoverDelegate {
    private let statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let popover = NSPopover()
    private let model: AppModel
    private var catcher: NSWindow?
    private var globalMonitor: Any?
    private var resignObserver: NSObjectProtocol?
    private var closing = false

    init(model: AppModel) {
        self.model = model
        super.init()
        let panel = PanelController(model: model)
        popover.contentViewController = panel
        popover.behavior = .applicationDefined
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
        if popover.isShown {
            closePanel()
            return
        }
        guard let button = statusItem.button else { return }
        NSApp.activate()
        popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
    }

    func popoverDidShow(_ notification: Notification) {
        watchOutsideClicks()
    }

    func popoverDidClose(_ notification: Notification) {
        retireCatcher()
    }

    /// A menu-bar-only app does not dismiss its panel when you click the desktop,
    /// another app, or another menu bar icon. A clear window under the panel takes
    /// those clicks, and a global monitor covers anything that window does not.
    /// The window stays allocated: releasing it from the click that hit it crashes.
    private func watchOutsideClicks() {
        removeClickMonitors()
        let panelWindow = popover.contentViewController?.view.window
        let panelLevel = panelWindow?.level ?? .popUpMenu
        let frame = NSScreen.screens.reduce(NSRect.null) { $0.union($1.frame) }
        guard frame.width > 1, frame.height > 1 else { return }
        let window = ensureCatcher()
        window.level = NSWindow.Level(rawValue: panelLevel.rawValue - 1)
        window.ignoresMouseEvents = false
        window.setFrame(frame, display: false)
        window.orderFrontRegardless()
        panelWindow?.orderFrontRegardless()

        let mask: NSEvent.EventTypeMask = [.leftMouseDown, .rightMouseDown, .otherMouseDown]
        globalMonitor = NSEvent.addGlobalMonitorForEvents(matching: mask) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.closePanel()
            }
        }
        resignObserver = NotificationCenter.default.addObserver(
            forName: NSApplication.didResignActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.closePanel()
            }
        }
    }

    private func ensureCatcher() -> NSWindow {
        if let catcher { return catcher }
        let window = OutsideClickWindow(
            contentRect: NSRect(x: 0, y: 0, width: 1, height: 1),
            styleMask: [.borderless],
            backing: .buffered,
            defer: false
        )
        window.backgroundColor = .clear
        window.isOpaque = false
        window.hasShadow = false
        window.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        let view = OutsideClickView(frame: NSRect(x: 0, y: 0, width: 1, height: 1))
        view.onMouseDown = { [weak self] in
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    self?.closePanel()
                }
            }
        }
        window.contentView = view
        catcher = window
        return window
    }

    private func retireCatcher() {
        removeClickMonitors()
        guard let window = catcher else { return }
        window.ignoresMouseEvents = true
        window.orderOut(nil)
    }

    private func removeClickMonitors() {
        if let globalMonitor {
            NSEvent.removeMonitor(globalMonitor)
            self.globalMonitor = nil
        }
        if let resignObserver {
            NotificationCenter.default.removeObserver(resignObserver)
            self.resignObserver = nil
        }
    }

    private func closePanel() {
        guard !closing else { return }
        let catcherUp = catcher?.isVisible == true && catcher?.ignoresMouseEvents == false
        guard popover.isShown || catcherUp else { return }
        closing = true
        if popover.isShown {
            popover.performClose(nil)
        }
        retireCatcher()
        closing = false
    }
}

private final class OutsideClickWindow: NSWindow {
    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
}

private final class OutsideClickView: NSView {
    var onMouseDown: (() -> Void)?

    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
    override func hitTest(_ point: NSPoint) -> NSView? { self }

    override func mouseDown(with event: NSEvent) { onMouseDown?() }
    override func rightMouseDown(with event: NSEvent) { onMouseDown?() }
    override func otherMouseDown(with event: NSEvent) { onMouseDown?() }
}
