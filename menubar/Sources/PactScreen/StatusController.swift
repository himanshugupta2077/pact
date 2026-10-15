import AppKit
import PactScreenCore

@MainActor
final class StatusController: NSObject, NSPopoverDelegate {
    private let statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let popover = NSPopover()
    private let model: AppModel
    private var shownTitle = ""
    private var shownBand: ScreenTimeBand?
    private var themeObserver: NSObjectProtocol?
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
        popover.contentSize = PanelController.preferredSize
        if let button = statusItem.button {
            button.target = self
            button.action = #selector(click)
            button.imageScaling = .scaleNone
            button.imagePosition = .imageOnly
        }
        model.onMenu = { [weak self] title, band in
            self?.apply(title: title, band: band)
        }
        apply(title: model.menuTitle, band: model.menuBand)
        themeObserver = DistributedNotificationCenter.default().addObserver(
            forName: Notification.Name("AppleInterfaceThemeChangedNotification"),
            object: nil,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.paint()
            }
        }
    }

    func apply(title: String, band: ScreenTimeBand?) {
        shownTitle = title
        shownBand = band
        paint()
    }

    private func paint() {
        guard let button = statusItem.button else { return }
        let image = Self.renderedTitle(shownTitle, band: shownBand, appearance: button.effectiveAppearance)
        image.isTemplate = false
        button.image = image
        button.image?.isTemplate = false
        button.title = ""
        statusItem.length = image.size.width
        button.setAccessibilityLabel("Screen time \(shownTitle)")
        button.toolTip = "Pact screen time"
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

extension StatusController {
    fileprivate static func renderedTitle(_ text: String, band: ScreenTimeBand?, appearance: NSAppearance) -> NSImage {
        let font = NSFont.monospacedDigitSystemFont(ofSize: 13, weight: .medium)
        let color = barColor(band: band, appearance: appearance)
        let attributed = NSAttributedString(string: text, attributes: [
            .font: font,
            .foregroundColor: color,
        ])
        let bounds = attributed.boundingRect(
            with: NSSize(width: 2400, height: 48),
            options: [.usesLineFragmentOrigin, .usesFontLeading]
        )
        let size = NSSize(width: max(10, ceil(bounds.width) + 10), height: 22)
        let scale = max(1, NSScreen.main?.backingScaleFactor ?? 2)
        let pixelsWide = max(1, Int((size.width * scale).rounded(.up)))
        let pixelsHigh = max(1, Int((size.height * scale).rounded(.up)))
        guard let rep = NSBitmapImageRep(
            bitmapDataPlanes: nil,
            pixelsWide: pixelsWide,
            pixelsHigh: pixelsHigh,
            bitsPerSample: 8,
            samplesPerPixel: 4,
            hasAlpha: true,
            isPlanar: false,
            colorSpaceName: .deviceRGB,
            bytesPerRow: 0,
            bitsPerPixel: 0
        ) else {
            let fallback = NSImage(size: size)
            fallback.isTemplate = false
            return fallback
        }
        rep.size = size
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
        NSColor.clear.set()
        NSRect(origin: .zero, size: size).fill()
        let y = (size.height - bounds.height) / 2 - bounds.origin.y
        attributed.draw(in: NSRect(x: 5, y: y, width: ceil(bounds.width) + 2, height: ceil(bounds.height)))
        NSGraphicsContext.restoreGraphicsState()
        let image = NSImage(size: size)
        image.addRepresentation(rep)
        image.isTemplate = false
        return image
    }

    private static func barColor(band: ScreenTimeBand?, appearance: NSAppearance) -> NSColor {
        let dark = appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
        let color: NSColor = switch band {
        case .green:
            dark
                ? NSColor(srgbRed: 0.30, green: 0.86, blue: 0.42, alpha: 1)
                : NSColor(srgbRed: 0.10, green: 0.52, blue: 0.22, alpha: 1)
        case .yellow:
            dark
                ? NSColor(srgbRed: 1.00, green: 0.82, blue: 0.20, alpha: 1)
                : NSColor(srgbRed: 0.70, green: 0.48, blue: 0.00, alpha: 1)
        case .red:
            dark
                ? NSColor(srgbRed: 1.00, green: 0.38, blue: 0.32, alpha: 1)
                : NSColor(srgbRed: 0.82, green: 0.16, blue: 0.13, alpha: 1)
        case nil:
            dark
                ? NSColor(srgbRed: 0.85, green: 0.85, blue: 0.88, alpha: 1)
                : NSColor(srgbRed: 0.20, green: 0.20, blue: 0.22, alpha: 1)
        }
        return color
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
