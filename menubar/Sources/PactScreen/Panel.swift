import AppKit
import Observation
import PactScreenCore

enum BucketPalette {
    static func color(_ id: String) -> NSColor {
        switch id {
        case "work":
            return NSColor(srgbRed: 0.36, green: 0.56, blue: 0.95, alpha: 1)
        case "personal":
            return NSColor(srgbRed: 0.86, green: 0.40, blue: 0.52, alpha: 1)
        case "entertainment":
            return NSColor(srgbRed: 0.95, green: 0.57, blue: 0.24, alpha: 1)
        case "gaming":
            return NSColor(srgbRed: 0.64, green: 0.47, blue: 0.96, alpha: 1)
        case "communication":
            return NSColor(srgbRed: 0.20, green: 0.70, blue: 0.55, alpha: 1)
        case "other", "unassigned":
            return NSColor(srgbRed: 0.62, green: 0.64, blue: 0.68, alpha: 1)
        default:
            let palette = [
                NSColor(srgbRed: 0.86, green: 0.46, blue: 0.58, alpha: 1),
                NSColor(srgbRed: 0.35, green: 0.74, blue: 0.82, alpha: 1),
                NSColor(srgbRed: 0.86, green: 0.74, blue: 0.36, alpha: 1),
            ]
            var hash = 0
            for byte in id.utf8 { hash = hash &* 31 &+ Int(byte) }
            return palette[abs(hash) % palette.count]
        }
    }
}

@MainActor
final class PanelController: NSViewController, NSTextFieldDelegate {
    static let preferredSize = NSSize(width: 480, height: 740)

    private let timeColumnWidth: CGFloat = 64
    private let percentColumnWidth: CGFloat = 84

    private let model: AppModel
    private var chart = 0
    private var filter = "all"
    private var query = ""
    private var showingSettings = false
    private var deviceFieldOrder: [String] = []
    private var deviceFields: [String: NSTextField] = [:]
    private var bucketToggleOrder: [String] = []

    private let dateLabel = Panel.caption("", size: 14, weight: .semibold, color: .labelColor)
    private let freshnessLabel = Panel.caption("", size: 11, weight: .regular, color: .secondaryLabelColor)
    private let nextButton = Panel.icon("chevron.right", tip: "Next day")
    private let settingsButton = Panel.icon("gearshape", tip: "Settings")
    private let totalLabel = Panel.caption("", size: 34, weight: .semibold, color: .labelColor)
    private let countLabel = Panel.caption("", size: 13, weight: .regular, color: .secondaryLabelColor)
    private let totalRow = Panel.hstack(8)
    private let emptyLabel = Panel.wrapping("")
    private let mainStack = Panel.vstack(18)
    private let categorySection = Panel.vstack(8)
    private let deviceSection = Panel.vstack(8)
    private let deviceStack = Panel.vstack(6)
    private let chartSection = Panel.vstack(8)
    private let settingsStack = Panel.vstack(14)
    private let chartChoice = ChoiceControl(titles: ["Hours", "Apps", "Devices"])
    private let chartCaption = Panel.wrapping("")
    private let chartHost = NSView()
    private let hourChart = HourChartView()
    private let filterStack = Panel.vstack(6)
    private let rowsStack = Panel.vstack(0)
    private let customStack = Panel.vstack(6)
    private let previewLabel = Panel.caption("", size: 13, weight: .medium, color: .labelColor)
    private let searchField = NSTextField()
    private let bucketField = NSTextField()
    private let bucketParent = NSPopUpButton()
    private let sshField = NSTextField()
    private let sshHint = Panel.caption("", size: 11, weight: .regular, color: .systemOrange)
    private let loginNote = Panel.wrapping("")
    private let loginToggle = NSButton(checkboxWithTitle: "Open at login", target: nil, action: nil)
    private let refreshButton = NSButton(title: "Refresh", target: nil, action: nil)
    private let menuBody = Panel.vstack(8)
    private var bucketToggles: [String: NSButton] = [:]
    private let deviceToggle = NSButton(checkboxWithTitle: "Each device", target: nil, action: nil)
    private let deviceFieldStack = Panel.vstack(6)
    private weak var scrollView: NSScrollView?
    /// Work, Personal, and any open personal group. Survives a refresh.
    private var openSections: Set<String> = []

    init(model: AppModel) {
        self.model = model
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    override func loadView() {
        let root = NSView(frame: NSRect(origin: .zero, size: Self.preferredSize))
        view = root
        let header = makeHeader()
        let scroll = makeScroll()
        let topLine = Panel.hairline()
        root.addSubview(header)
        root.addSubview(topLine)
        root.addSubview(scroll)
        NSLayoutConstraint.activate([
            header.topAnchor.constraint(equalTo: root.topAnchor),
            header.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            header.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            topLine.topAnchor.constraint(equalTo: header.bottomAnchor),
            topLine.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            topLine.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            scroll.topAnchor.constraint(equalTo: topLine.bottomAnchor),
            scroll.bottomAnchor.constraint(equalTo: root.bottomAnchor),
            scroll.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: root.trailingAnchor),
        ])
        wire()
        render()
        watch()
    }

    func revealSettings() {
        showingSettings = true
        render()
    }

    /// Opens Work, Personal, and each personal group. Used by the render dump.
    func revealCategories() {
        openSections = [Bucket.work.id, Bucket.personal.id]
        if let rollup = model.dayRollup {
            for item in classConstituents(rollup, classId: Bucket.personal.id) {
                openSections.insert(item.id)
            }
        }
        render()
    }

    private func watch() {
        withObservationTracking { [weak self] in
            guard let self else { return }
            _ = self.model.day
            _ = self.model.freshness
            _ = self.model.dayRollup
            _ = self.model.preferences
            _ = self.model.refreshing
            _ = self.model.errorText
            _ = self.model.loginNote
            _ = self.model.menuTitle
        } onChange: { [weak self] in
            Task { @MainActor in
                self?.render()
                self?.watch()
            }
        }
    }

    private func makeHeader() -> NSView {
        nextButton.target = self
        nextButton.action = #selector(nextDay)
        settingsButton.target = self
        settingsButton.action = #selector(toggleSettings)
        let previous = Panel.icon("chevron.left", tip: "Previous day")
        previous.target = self
        previous.action = #selector(previousDay)
        freshnessLabel.maximumNumberOfLines = 2
        freshnessLabel.alignment = .center
        dateLabel.alignment = .center
        let titles = Panel.vstack(2)
        titles.alignment = .centerX
        titles.addArrangedSubview(dateLabel)
        titles.addArrangedSubview(freshnessLabel)
        let row = NSView()
        row.translatesAutoresizingMaskIntoConstraints = false
        for item in [previous, titles, nextButton, settingsButton] {
            item.translatesAutoresizingMaskIntoConstraints = false
            row.addSubview(item)
        }
        NSLayoutConstraint.activate([
            row.heightAnchor.constraint(equalToConstant: 52),
            previous.leadingAnchor.constraint(equalTo: row.leadingAnchor, constant: 12),
            previous.centerYAnchor.constraint(equalTo: row.centerYAnchor),
            settingsButton.trailingAnchor.constraint(equalTo: row.trailingAnchor, constant: -12),
            settingsButton.centerYAnchor.constraint(equalTo: row.centerYAnchor),
            nextButton.trailingAnchor.constraint(equalTo: settingsButton.leadingAnchor, constant: -4),
            nextButton.centerYAnchor.constraint(equalTo: row.centerYAnchor),
            titles.centerXAnchor.constraint(equalTo: row.centerXAnchor),
            titles.centerYAnchor.constraint(equalTo: row.centerYAnchor),
            titles.leadingAnchor.constraint(greaterThanOrEqualTo: previous.trailingAnchor, constant: 8),
            nextButton.leadingAnchor.constraint(greaterThanOrEqualTo: titles.trailingAnchor, constant: 8),
        ])
        return row
    }

    private func makeScroll() -> NSScrollView {
        let scroll = NSScrollView()
        scroll.drawsBackground = false
        scroll.hasVerticalScroller = true
        scroll.autohidesScrollers = true
        scroll.translatesAutoresizingMaskIntoConstraints = false
        scrollView = scroll
        let document = FlippedView()
        document.translatesAutoresizingMaskIntoConstraints = false
        scroll.documentView = document
        let content = Panel.vstack(12)
        buildContent(content)
        document.addSubview(content)
        NSLayoutConstraint.activate([
            content.topAnchor.constraint(equalTo: document.topAnchor, constant: 14),
            content.leadingAnchor.constraint(equalTo: document.leadingAnchor, constant: 16),
            content.trailingAnchor.constraint(equalTo: document.trailingAnchor, constant: -16),
            content.bottomAnchor.constraint(equalTo: document.bottomAnchor, constant: -16),
            document.widthAnchor.constraint(equalTo: scroll.contentView.widthAnchor),
        ])
        return scroll
    }

    private func buildContent(_ content: NSStackView) {
        totalLabel.font = .systemFont(ofSize: 34, weight: .semibold)
        countLabel.setContentHuggingPriority(.required, for: .horizontal)
        countLabel.setContentCompressionResistancePriority(.required, for: .horizontal)
        totalRow.addArrangedSubview(totalLabel)
        totalRow.addArrangedSubview(Panel.spacer())
        totalRow.addArrangedSubview(countLabel)

        chartChoice.onSelect = { [weak self] index in
            self?.chart = index
            self?.render()
        }
        chartHost.translatesAutoresizingMaskIntoConstraints = false
        hourChart.translatesAutoresizingMaskIntoConstraints = false
        hourChart.heightAnchor.constraint(equalToConstant: 128).isActive = true

        let deviceTitle = Panel.caption("Devices", size: 13, weight: .semibold, color: .labelColor)
        let line = Panel.hairline()
        deviceSection.addArrangedSubview(line)
        deviceSection.addArrangedSubview(deviceTitle)
        deviceSection.addArrangedSubview(deviceStack)
        Panel.pin(line, to: deviceSection)
        Panel.pin(deviceStack, to: deviceSection)

        let chartLine = Panel.hairline()
        chartSection.addArrangedSubview(chartLine)
        chartSection.addArrangedSubview(chartChoice)
        chartSection.addArrangedSubview(chartCaption)
        chartSection.addArrangedSubview(chartHost)
        Panel.pin(chartLine, to: chartSection)
        Panel.pin(chartChoice, to: chartSection)
        Panel.pin(chartHost, to: chartSection)

        mainStack.addArrangedSubview(totalRow)
        mainStack.addArrangedSubview(categorySection)
        mainStack.addArrangedSubview(deviceSection)
        mainStack.addArrangedSubview(chartSection)
        for item in [totalRow, categorySection, deviceSection, chartSection] {
            Panel.pin(item, to: mainStack)
        }

        buildSettings()
        content.addArrangedSubview(emptyLabel)
        content.addArrangedSubview(mainStack)
        content.addArrangedSubview(settingsStack)
        for item in [emptyLabel, mainStack, settingsStack] {
            Panel.pin(item, to: content)
        }
    }

    private func buildSettings() {
        let done = plainButton("Done", action: #selector(closeSettings), color: .controlAccentColor)
        let doneRow = Panel.hstack(8)
        doneRow.addArrangedSubview(Panel.caption("Settings", size: 16, weight: .semibold, color: .labelColor))
        doneRow.addArrangedSubview(Panel.spacer())
        doneRow.addArrangedSubview(done)

        previewLabel.font = .monospacedDigitSystemFont(ofSize: 13, weight: .medium)
        sshField.placeholderString = "pact@pact-host"
        sshField.delegate = self
        sshField.bezelStyle = .roundedBezel
        sshField.font = .systemFont(ofSize: 12)
        sshHint.maximumNumberOfLines = 2
        let totalToggle = NSButton(checkboxWithTitle: "Total", target: self, action: #selector(totalToggled(_:)))
        totalToggle.identifier = NSUserInterfaceItemIdentifier("show-total")
        bucketToggles["__total"] = totalToggle
        deviceToggle.target = self
        deviceToggle.action = #selector(devicesToggled(_:))
        let sshRow = Panel.hstack(8)
        sshRow.addArrangedSubview(Panel.caption("VPS", size: 12, weight: .regular, color: .secondaryLabelColor))
        sshRow.addArrangedSubview(sshField)
        sshField.setContentHuggingPriority(.defaultLow, for: .horizontal)
        menuBody.addArrangedSubview(previewLabel)
        menuBody.addArrangedSubview(totalToggle)
        menuBody.addArrangedSubview(deviceToggle)
        menuBody.addArrangedSubview(deviceFieldStack)
        menuBody.addArrangedSubview(sshRow)
        menuBody.addArrangedSubview(sshHint)
        Panel.pin(sshRow, to: menuBody)
        Panel.pin(deviceFieldStack, to: menuBody)
        Panel.pin(sshHint, to: menuBody)

        refreshButton.target = self
        refreshButton.action = #selector(refresh)
        refreshButton.bezelStyle = .shadowlessSquare
        refreshButton.isBordered = false
        refreshButton.font = .systemFont(ofSize: 13, weight: .semibold)
        refreshButton.contentTintColor = .controlAccentColor
        refreshButton.setContentHuggingPriority(.required, for: .horizontal)
        loginToggle.target = self
        loginToggle.action = #selector(loginToggled)
        loginToggle.font = .systemFont(ofSize: 13)
        let quit = plainButton("Quit", action: #selector(quit), color: .secondaryLabelColor)
        let actions = Panel.hstack(8)
        actions.addArrangedSubview(refreshButton)
        actions.addArrangedSubview(Panel.spacer())
        actions.addArrangedSubview(quit)

        searchField.placeholderString = "Search"
        searchField.delegate = self
        searchField.bezelStyle = .roundedBezel
        searchField.font = .systemFont(ofSize: 13)
        bucketField.placeholderString = "New bucket"
        bucketField.delegate = self
        bucketField.bezelStyle = .roundedBezel
        bucketField.font = .systemFont(ofSize: 13)
        bucketParent.controlSize = .small
        bucketParent.font = .systemFont(ofSize: 12)
        bucketParent.addItem(withTitle: Bucket.personal.name)
        bucketParent.lastItem?.representedObject = Bucket.personal.id
        bucketParent.addItem(withTitle: Bucket.work.name)
        bucketParent.lastItem?.representedObject = Bucket.work.id
        bucketParent.widthAnchor.constraint(equalToConstant: 110).isActive = true
        let add = NSButton(title: "Add", target: self, action: #selector(addBucket))
        add.bezelStyle = .rounded
        let addRow = Panel.hstack(8)
        addRow.addArrangedSubview(bucketField)
        addRow.addArrangedSubview(bucketParent)
        addRow.addArrangedSubview(add)
        bucketField.setContentHuggingPriority(.defaultLow, for: .horizontal)

        let menuLine = Panel.hairline()
        let editorLine = Panel.hairline()
        settingsStack.addArrangedSubview(doneRow)
        settingsStack.addArrangedSubview(Panel.caption("Menu bar", size: 13, weight: .semibold, color: .labelColor))
        settingsStack.addArrangedSubview(menuBody)
        settingsStack.addArrangedSubview(menuLine)
        settingsStack.addArrangedSubview(actions)
        settingsStack.addArrangedSubview(loginToggle)
        settingsStack.addArrangedSubview(loginNote)
        settingsStack.addArrangedSubview(editorLine)
        settingsStack.addArrangedSubview(Panel.caption("Categories", size: 13, weight: .semibold, color: .labelColor))
        settingsStack.addArrangedSubview(searchField)
        settingsStack.addArrangedSubview(filterStack)
        settingsStack.addArrangedSubview(rowsStack)
        settingsStack.addArrangedSubview(customStack)
        settingsStack.addArrangedSubview(addRow)
        for item in [doneRow, menuBody, menuLine, actions, loginNote, editorLine, searchField, filterStack, rowsStack, customStack, addRow] {
            Panel.pin(item, to: settingsStack)
        }
    }

    private func plainButton(_ title: String, action: Selector, color: NSColor) -> NSButton {
        let button = NSButton(title: title, target: self, action: action)
        button.bezelStyle = .shadowlessSquare
        button.isBordered = false
        button.font = .systemFont(ofSize: 13, weight: .semibold)
        button.contentTintColor = color
        button.setContentHuggingPriority(.required, for: .horizontal)
        return button
    }

    private func wire() {
        sshField.stringValue = model.preferences.ssh
    }

    private func render() {
        let savedOffset = scrollView?.contentView.bounds.origin
        let day = model.day.isEmpty ? DayClock.today() : model.day
        dateLabel.stringValue = formatDay(day)
        freshnessLabel.stringValue = model.freshness
        nextButton.isEnabled = day != DayClock.today()
        refreshButton.isEnabled = !model.refreshing
        loginToggle.state = model.preferences.openAtLogin ? .on : .off
        loginNote.stringValue = model.loginNote ?? ""
        loginNote.isHidden = model.loginNote == nil
        chartChoice.selected = chart
        settingsButton.contentTintColor = showingSettings ? .controlAccentColor : .secondaryLabelColor
        settingsButton.image = NSImage(
            systemSymbolName: showingSettings ? "gearshape.fill" : "gearshape",
            accessibilityDescription: "Settings"
        )

        settingsStack.isHidden = !showingSettings
        if showingSettings {
            emptyLabel.isHidden = true
            mainStack.isHidden = true
            renderSettings()
            return
        }

        let daySeconds = model.dayRollup?.screenSeconds ?? 0
        let hasTime = daySeconds > 0
        emptyLabel.isHidden = hasTime
        mainStack.isHidden = !hasTime
        if !hasTime {
            if model.refreshing, model.dayRollup == nil {
                emptyLabel.stringValue = "Fetching from the VPS…"
            } else if let error = model.errorText, model.dayRollup == nil {
                emptyLabel.stringValue = error
            } else {
                emptyLabel.stringValue = "Nothing has been shipped for this day. Collectors run every 5 minutes."
            }
            return
        }

        let rollup = model.dayRollup ?? DayRollup.empty(day: day)
        totalLabel.stringValue = formatDuration(rollup.screenSeconds)
        let count = (model.dayRollup?.devices ?? []).filter { $0.seconds > 0 }.count
        countLabel.stringValue = count == 1 ? "1 device" : "\(count) devices"
        renderBreakdown(rollup)
        renderDevices(model.dayRollup)
        renderChart(model.dayRollup)
        restoreScroll(savedOffset)
    }

    private func restoreScroll(_ origin: NSPoint?) {
        guard let origin, let scrollView else { return }
        scrollView.layoutSubtreeIfNeeded()
        let clip = scrollView.contentView
        clip.scroll(to: origin)
        scrollView.reflectScrolledClipView(clip)
    }

    private func renderBreakdown(_ rollup: DayRollup) {
        Panel.clear(categorySection)
        let total = rollup.screenSeconds

        let heading = Panel.hstack(8)
        heading.addArrangedSubview(Panel.caption("Categories", size: 13, weight: .semibold, color: .labelColor))
        categorySection.addArrangedSubview(heading)
        Panel.pin(heading, to: categorySection)

        let header = columnHeader()
        categorySection.addArrangedSubview(header)
        Panel.pin(header, to: categorySection)

        let list = Panel.vstack(2)
        for id in [Bucket.work.id, Bucket.personal.id] {
            let name = id == Bucket.work.id ? Bucket.work.name : Bucket.personal.name
            let seconds = rollup.classes.first { $0.id == id }?.seconds ?? 0
            let items = classConstituents(rollup, classId: id)
            let canOpen = seconds > 0.5 || !items.isEmpty
            let open = canOpen && openSections.contains(id)
            addRow(
                to: list,
                name: name,
                color: BucketPalette.color(id),
                seconds: seconds,
                total: total,
                strong: true,
                indent: 0,
                disclosure: disclosure(canOpen: canOpen, open: open),
                onClick: canOpen ? { [weak self] in self?.toggleSection(id) } : nil
            )
            guard open else { continue }
            if id == Bucket.work.id {
                if items.isEmpty {
                    addNote("Nothing in Work for this day.", indent: 28, to: list)
                } else {
                    appendLeaves(items, total: total, indent: 18, to: list)
                }
            } else if items.isEmpty {
                addNote("Nothing in Personal for this day.", indent: 28, to: list)
            } else {
                for item in items {
                    let hasChildren = !item.children.isEmpty
                    let groupOpen = hasChildren && openSections.contains(item.id)
                    addRow(
                        to: list,
                        name: item.name,
                        color: BucketPalette.color(item.colorId),
                        seconds: item.seconds,
                        total: total,
                        strong: false,
                        indent: 18,
                        disclosure: disclosure(canOpen: hasChildren, open: groupOpen),
                        onClick: hasChildren ? { [weak self] in self?.toggleSection(item.id) } : nil
                    )
                    if groupOpen {
                        appendLeaves(item.children, total: total, indent: 36, to: list)
                    }
                }
            }
        }
        categorySection.addArrangedSubview(list)
        Panel.pin(list, to: categorySection)
    }

    private func toggleSection(_ id: String) {
        if openSections.contains(id) {
            openSections.remove(id)
        } else {
            openSections.insert(id)
        }
        render()
    }

    private func disclosure(canOpen: Bool, open: Bool) -> Disclosure {
        guard canOpen else { return .none }
        return open ? .open : .closed
    }

    /// Apps, and a URLs row with each site under it. No extra click.
    private func appendLeaves(_ items: [BreakdownItem], total: Double, indent: CGFloat, to list: NSStackView) {
        for item in items {
            addRow(
                to: list,
                name: item.name,
                color: BucketPalette.color(item.colorId),
                seconds: item.seconds,
                total: total,
                strong: false,
                indent: indent,
                disclosure: .none,
                onClick: nil
            )
            if !item.children.isEmpty {
                appendLeaves(item.children, total: total, indent: indent + 16, to: list)
            }
        }
    }

    private func addNote(_ text: String, indent: CGFloat, to list: NSStackView) {
        let row = Panel.hstack(8)
        row.addArrangedSubview(Panel.gap(indent))
        let note = Panel.wrapping(text)
        row.addArrangedSubview(note)
        list.addArrangedSubview(row)
        Panel.pin(row, to: list)
    }

    private func addRow(
        to list: NSStackView,
        name: String,
        color: NSColor,
        seconds: Double,
        total: Double,
        strong: Bool,
        indent: CGFloat,
        disclosure: Disclosure,
        onClick: (() -> Void)?
    ) {
        let row = metricRow(
            name: name,
            color: color,
            seconds: seconds,
            total: total,
            strong: strong,
            indent: indent,
            disclosure: disclosure,
            onClick: onClick
        )
        list.addArrangedSubview(row)
        Panel.pin(row, to: list)
    }

    private func columnHeader() -> NSView {
        let row = Panel.hstack(8)
        row.addArrangedSubview(Panel.spacer())
        row.addArrangedSubview(timeCaption("Day", width: timeColumnWidth))
        row.addArrangedSubview(timeCaption("Percentage", width: percentColumnWidth))
        return row
    }

    private func timeCaption(_ text: String, width: CGFloat) -> NSTextField {
        let field = Panel.caption(text, size: 11, weight: .medium, color: .secondaryLabelColor)
        field.alignment = .right
        field.font = .systemFont(ofSize: 11, weight: .medium)
        field.widthAnchor.constraint(equalToConstant: width).isActive = true
        return field
    }

    private func metricRow(
        name: String,
        color: NSColor,
        seconds: Double,
        total: Double,
        strong: Bool,
        indent: CGFloat,
        disclosure: Disclosure,
        onClick: (() -> Void)?
    ) -> NSView {
        let row = Panel.hstack(8)
        let weight: NSFont.Weight = strong ? .semibold : .regular
        if indent > 0 {
            row.addArrangedSubview(Panel.gap(indent))
        }
        row.addArrangedSubview(disclosureMark(disclosure))
        row.addArrangedSubview(DotView(color: color))
        let title = Panel.caption(name, size: 13, weight: weight, color: .labelColor)
        title.toolTip = name
        row.addArrangedSubview(title)
        row.addArrangedSubview(Panel.spacer())
        let dayLabel = timeValue(formatDuration(seconds), weight: weight, width: timeColumnWidth, primary: true)
        let share = timeValue(percentText(seconds, of: total), weight: weight, width: percentColumnWidth, primary: false)
        row.addArrangedSubview(dayLabel)
        row.addArrangedSubview(share)
        dayLabel.setContentCompressionResistancePriority(.required, for: .horizontal)
        share.setContentCompressionResistancePriority(.required, for: .horizontal)
        guard let onClick else { return row }
        let hit = ClickRow()
        hit.onClick = onClick
        hit.translatesAutoresizingMaskIntoConstraints = false
        hit.addSubview(row)
        NSLayoutConstraint.activate([
            row.topAnchor.constraint(equalTo: hit.topAnchor, constant: 2),
            row.bottomAnchor.constraint(equalTo: hit.bottomAnchor, constant: -2),
            row.leadingAnchor.constraint(equalTo: hit.leadingAnchor),
            row.trailingAnchor.constraint(equalTo: hit.trailingAnchor),
        ])
        return hit
    }

    private func disclosureMark(_ state: Disclosure) -> NSView {
        let symbol: String?
        let tip: String
        switch state {
        case .none:
            symbol = nil
            tip = ""
        case .closed:
            symbol = "chevron.right"
            tip = "Show"
        case .open:
            symbol = "chevron.down"
            tip = "Hide"
        }
        guard let symbol, let image = NSImage(systemSymbolName: symbol, accessibilityDescription: tip) else {
            return Panel.gap(12)
        }
        image.isTemplate = true
        let icon = NSImageView()
        icon.image = image
        icon.contentTintColor = .tertiaryLabelColor
        icon.symbolConfiguration = NSImage.SymbolConfiguration(pointSize: 9, weight: .bold)
        icon.imageScaling = .scaleProportionallyDown
        icon.translatesAutoresizingMaskIntoConstraints = false
        icon.widthAnchor.constraint(equalToConstant: 12).isActive = true
        icon.heightAnchor.constraint(equalToConstant: 12).isActive = true
        return icon
    }

    private func timeValue(_ text: String, weight: NSFont.Weight, width: CGFloat, primary: Bool) -> NSTextField {
        let field = Panel.caption(text, size: 13, weight: weight, color: primary ? .labelColor : .secondaryLabelColor)
        field.font = .monospacedDigitSystemFont(ofSize: 13, weight: weight)
        field.alignment = .right
        field.widthAnchor.constraint(equalToConstant: width).isActive = true
        return field
    }

    private func renderDevices(_ rollup: DayRollup?) {
        Panel.clear(deviceStack)
        let devices = (rollup?.devices ?? []).filter { $0.seconds > 0 }
        if devices.isEmpty {
            let note = Panel.wrapping("Nothing on this day.")
            deviceStack.addArrangedSubview(note)
            Panel.pin(note, to: deviceStack)
            return
        }
        for device in devices {
            let row = Panel.hstack(8)
            let label = preferredDeviceName(device.name, override: model.preferences.deviceLabels[device.deviceId])
            row.addArrangedSubview(deviceIcon(platform: device.platform, name: label))
            row.addArrangedSubview(Panel.caption(label, size: 13, weight: .regular, color: .labelColor))
            row.addArrangedSubview(Panel.spacer())
            let time = Panel.caption(formatDuration(device.seconds), size: 13, weight: .regular, color: .labelColor)
            time.font = .monospacedDigitSystemFont(ofSize: 13, weight: .regular)
            time.alignment = .right
            row.addArrangedSubview(time)
            time.setContentCompressionResistancePriority(.required, for: .horizontal)
            deviceStack.addArrangedSubview(row)
            Panel.pin(row, to: deviceStack)
        }
    }

    private func deviceIcon(platform: String, name: String) -> NSImageView {
        let icon = NSImageView()
        let symbol = deviceSymbol(platform: platform, name: name)
        if let image = NSImage(systemSymbolName: symbol, accessibilityDescription: name) {
            image.isTemplate = true
            icon.image = image
        }
        icon.contentTintColor = .secondaryLabelColor
        icon.symbolConfiguration = NSImage.SymbolConfiguration(pointSize: 14, weight: .medium)
        icon.imageScaling = .scaleProportionallyDown
        icon.widthAnchor.constraint(equalToConstant: 18).isActive = true
        icon.heightAnchor.constraint(equalToConstant: 18).isActive = true
        return icon
    }

    private func renderChart(_ rollup: DayRollup?) {
        chartCaption.stringValue = [
            "Hour by hour, Kolkata time. Colors are Work and Personal.",
            "The program in front of you. A browser includes every site opened in it.",
            "Each device, split into Work and Personal.",
        ][chart]
        for view in chartHost.subviews { view.removeFromSuperview() }
        let child: NSView
        switch chart {
        case 1:
            child = appChart(rollup?.apps ?? [])
        case 2:
            child = deviceChart(rollup)
        default:
            if rollup == nil || rollup?.hours.allSatisfy({ $0.seconds <= 0 }) == true {
                child = Panel.wrapping("No hour-by-hour record for this day.")
            } else if let rollup {
                let ids = workIds()
                hourChart.hours = rollup.hours.map { hour in
                    HourTotal(hour: hour.hour, buckets: classParts(hour.buckets, workBucketIds: ids))
                }
                child = hourChart
            } else {
                child = Panel.wrapping("No hour-by-hour record for this day.")
            }
        }
        child.translatesAutoresizingMaskIntoConstraints = false
        chartHost.addSubview(child)
        NSLayoutConstraint.activate([
            child.topAnchor.constraint(equalTo: chartHost.topAnchor),
            child.leadingAnchor.constraint(equalTo: chartHost.leadingAnchor),
            child.trailingAnchor.constraint(equalTo: chartHost.trailingAnchor),
            child.bottomAnchor.constraint(equalTo: chartHost.bottomAnchor),
        ])
    }

    private func workIds() -> Set<String> {
        Set(model.preferences.buckets.filter { $0.parent == Bucket.work.id }.map(\.id))
    }

    private func appChart(_ apps: [AppTotal]) -> NSView {
        let limit = 8
        var shown = apps.count > limit ? Array(apps.prefix(limit)) : apps
        if apps.count > limit {
            let rest = apps.dropFirst(limit).reduce(0) { $0 + $1.seconds }
            if rest > 0 { shown.append(AppTotal(name: "Other apps", seconds: rest)) }
        }
        let stack = Panel.vstack(8)
        let peak = shown.map(\.seconds).max() ?? 1
        if shown.isEmpty {
            stack.addArrangedSubview(Panel.wrapping("No apps for this day."))
            return stack
        }
        for app in shown {
            let name = Panel.caption(app.name, size: 13, weight: .regular, color: .labelColor)
            let time = Panel.caption(formatDuration(app.seconds), size: 13, weight: .regular, color: .labelColor)
            time.font = .monospacedDigitSystemFont(ofSize: 13, weight: .regular)
            let row = Panel.hstack(8)
            row.addArrangedSubview(name)
            row.addArrangedSubview(Panel.spacer())
            row.addArrangedSubview(time)
            let meter = MeterView(fraction: app.seconds / peak, fill: .controlAccentColor)
            let block = Panel.vstack(3)
            block.addArrangedSubview(row)
            block.addArrangedSubview(meter)
            Panel.pin(row, to: block)
            Panel.pin(meter, to: block)
            stack.addArrangedSubview(block)
            Panel.pin(block, to: stack)
        }
        return stack
    }

    private func deviceChart(_ rollup: DayRollup?) -> NSView {
        let stack = Panel.vstack(12)
        let devices = rollup?.devices ?? []
        if devices.isEmpty {
            stack.addArrangedSubview(Panel.wrapping("No devices for this day."))
            return stack
        }
        let peak = devices.map(\.seconds).max() ?? 1
        let ids = workIds()
        for device in devices {
            let name = Panel.caption(
                preferredDeviceName(device.name, override: model.preferences.deviceLabels[device.deviceId]),
                size: 13,
                weight: .regular,
                color: .labelColor
            )
            let time = Panel.caption(formatDuration(device.seconds), size: 13, weight: .regular, color: .labelColor)
            time.font = .monospacedDigitSystemFont(ofSize: 13, weight: .regular)
            let row = Panel.hstack(8)
            row.addArrangedSubview(name)
            row.addArrangedSubview(Panel.spacer())
            row.addArrangedSubview(time)
            let parts = classParts(device.buckets, workBucketIds: ids)
            let bar = StackedBarView(parts: parts)
            let detail = Panel.caption(
                parts.map { "\($0.name) \(formatDuration($0.seconds))" }.joined(separator: " · "),
                size: 11,
                weight: .regular,
                color: .secondaryLabelColor
            )
            detail.maximumNumberOfLines = 2
            let meter = MeterView(fraction: peak > 0 ? device.seconds / peak : 0, fill: .secondaryLabelColor)
            let block = Panel.vstack(4)
            block.addArrangedSubview(row)
            block.addArrangedSubview(bar)
            block.addArrangedSubview(detail)
            block.addArrangedSubview(meter)
            for item in [row, bar, detail, meter] { Panel.pin(item, to: block) }
            stack.addArrangedSubview(block)
            Panel.pin(block, to: stack)
        }
        return stack
    }

    private func renderSettings() {
        renderMenu(model.dayRollup)
        renderCategoryEditor(model.dayRollup)
        renderBucketList()
    }

    private func renderCategoryEditor(_ rollup: DayRollup?) {
        var live = assignmentIds().union(["all", Bucket.personal.id])
        if rollup?.categories.contains(where: { $0.bucketId == Bucket.unassigned.id }) == true {
            live.insert(Bucket.unassigned.id)
        }
        if !live.contains(filter) { filter = "all" }
        renderFilters(rollup)
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let rows = (rollup?.categories ?? []).filter { row in
            if !needle.isEmpty {
                let hay = (row.name + " " + row.category).lowercased()
                if !hay.contains(needle) { return false }
            }
            return rowMatches(row)
        }
        Panel.clear(rowsStack)
        let groups = categoryGroups(rows)
        if groups.isEmpty {
            rowsStack.addArrangedSubview(Panel.wrapping("Nothing in this list."))
        }
        for group in groups {
            if let title = group.title {
                let header = groupHeader(title, seconds: group.seconds)
                rowsStack.addArrangedSubview(header)
                Panel.pin(header, to: rowsStack)
                for row in group.rows {
                    let line = categoryRow(row, nested: true)
                    rowsStack.addArrangedSubview(line)
                    Panel.pin(line, to: rowsStack)
                }
            } else if let row = group.rows.first {
                let line = categoryRow(row, nested: false)
                rowsStack.addArrangedSubview(line)
                Panel.pin(line, to: rowsStack)
            }
        }
    }

    private func renderFilters(_ rollup: DayRollup?) {
        Panel.clear(filterStack)
        var tags: [(String, String)] = [
            ("All", "all"),
            (Bucket.work.name, Bucket.work.id),
            (Bucket.personal.name, Bucket.personal.id),
        ]
        tags += model.preferences.buckets.map { ($0.name, $0.id) }
        if rollup?.categories.contains(where: { $0.bucketId == Bucket.unassigned.id }) == true {
            tags.append((Bucket.unassigned.name, Bucket.unassigned.id))
        }
        var row = Panel.hstack(6)
        var used: CGFloat = 0
        let maxWidth: CGFloat = 430
        func flush() {
            guard !row.arrangedSubviews.isEmpty else { return }
            row.addArrangedSubview(Panel.spacer())
            filterStack.addArrangedSubview(row)
            Panel.pin(row, to: filterStack)
            row = Panel.hstack(6)
            used = 0
        }
        for (title, id) in tags {
            let button = NSButton(title: title, target: self, action: #selector(filterTapped(_:)))
            button.identifier = NSUserInterfaceItemIdentifier(id)
            button.bezelStyle = .recessed
            button.setButtonType(.momentaryPushIn)
            button.font = .systemFont(ofSize: 11, weight: filter == id ? .semibold : .regular)
            button.contentTintColor = filter == id ? .controlAccentColor : .labelColor
            let width = button.fittingSize.width
            if used > 0, used + width > maxWidth { flush() }
            row.addArrangedSubview(button)
            used += width + 6
        }
        flush()
    }

    private func groupHeader(_ title: String, seconds: Double) -> NSView {
        let line = Panel.hstack(8)
        line.edgeInsets = NSEdgeInsets(top: 10, left: 0, bottom: 2, right: 0)
        let name = Panel.caption(title, size: 13, weight: .semibold, color: .labelColor)
        let time = Panel.caption(formatDuration(seconds), size: 13, weight: .semibold, color: .labelColor)
        time.font = .monospacedDigitSystemFont(ofSize: 13, weight: .semibold)
        time.alignment = .right
        line.addArrangedSubview(name)
        line.addArrangedSubview(Panel.spacer())
        line.addArrangedSubview(time)
        time.setContentCompressionResistancePriority(.required, for: .horizontal)
        return line
    }

    private func categoryRow(_ row: CategoryRow, nested: Bool) -> NSView {
        let line = Panel.hstack(8)
        line.edgeInsets = NSEdgeInsets(top: 6, left: nested ? 16 : 0, bottom: 6, right: 0)
        let dot = DotView(color: BucketPalette.color(row.bucketId))
        let name = Panel.caption(row.name, size: 13, weight: .regular, color: .labelColor)
        let sub = Panel.caption("\(row.category) · \(row.marked ? "marked" : "default")", size: 10, weight: .regular, color: .secondaryLabelColor)
        let text = Panel.vstack(1)
        text.addArrangedSubview(name)
        text.addArrangedSubview(sub)
        let time = Panel.caption(formatDuration(row.seconds), size: 13, weight: .regular, color: .labelColor)
        time.font = .monospacedDigitSystemFont(ofSize: 13, weight: .regular)
        let popup = NSPopUpButton()
        popup.controlSize = .small
        popup.font = .systemFont(ofSize: 12)
        popup.autoenablesItems = false
        fillAssignmentPopup(popup, row: row)
        popup.target = self
        popup.action = #selector(bucketPicked(_:))
        popup.identifier = NSUserInterfaceItemIdentifier(row.category)
        popup.widthAnchor.constraint(equalToConstant: 120).isActive = true
        line.addArrangedSubview(dot)
        line.addArrangedSubview(text)
        line.addArrangedSubview(Panel.spacer())
        line.addArrangedSubview(time)
        line.addArrangedSubview(popup)
        time.setContentCompressionResistancePriority(.required, for: .horizontal)
        if row.marked {
            let reset = Panel.icon("arrow.uturn.backward", tip: "Use the default again")
            reset.target = self
            reset.action = #selector(resetMark(_:))
            reset.identifier = NSUserInterfaceItemIdentifier(row.category)
            line.addArrangedSubview(reset)
        }
        return line
    }

    private func assignmentIds() -> Set<String> {
        Set([Bucket.work.id, Bucket.personal.id] + model.preferences.buckets.map(\.id))
    }

    private func classOf(_ bucketId: String) -> String {
        if bucketId == Bucket.work.id { return Bucket.work.id }
        if let bucket = model.preferences.buckets.first(where: { $0.id == bucketId }) {
            return bucket.parent == Bucket.work.id ? Bucket.work.id : Bucket.personal.id
        }
        return Bucket.personal.id
    }

    private func rowMatches(_ row: CategoryRow) -> Bool {
        if filter == "all" { return true }
        if filter == Bucket.work.id { return classOf(row.bucketId) == Bucket.work.id }
        if filter == Bucket.personal.id { return classOf(row.bucketId) == Bucket.personal.id }
        return row.bucketId == filter
    }

    private func fillAssignmentPopup(_ popup: NSPopUpButton, row: CategoryRow) {
        popup.removeAllItems()
        func add(_ id: String, _ name: String) {
            popup.addItem(withTitle: name)
            popup.lastItem?.representedObject = id
            popup.lastItem?.isEnabled = true
        }
        add(Bucket.work.id, Bucket.work.name)
        for bucket in model.preferences.buckets where bucket.parent == Bucket.work.id {
            add(bucket.id, bucket.name)
        }
        popup.menu?.addItem(.separator())
        add(Bucket.personal.id, Bucket.personal.name)
        for bucket in model.preferences.buckets where bucket.parent != Bucket.work.id {
            add(bucket.id, bucket.name)
        }
        if !assignmentIds().contains(row.bucketId) {
            add(Bucket.unassigned.id, Bucket.unassigned.name)
        }
        if let item = popup.menu?.items.first(where: { ($0.representedObject as? String) == row.bucketId }) {
            popup.select(item)
        }
    }

    private func renderBucketList() {
        Panel.clear(customStack)
        customStack.addArrangedSubview(Panel.caption("Buckets", size: 11, weight: .semibold, color: .secondaryLabelColor))
        if model.preferences.buckets.isEmpty {
            let empty = Panel.wrapping("No buckets yet. Time that is not in a bucket stays in the class you assign.")
            customStack.addArrangedSubview(empty)
            Panel.pin(empty, to: customStack)
        }
        for bucket in model.preferences.buckets {
            let row = Panel.hstack(8)
            row.addArrangedSubview(DotView(color: BucketPalette.color(bucket.id)))
            row.addArrangedSubview(Panel.caption(bucket.name, size: 13, weight: .regular, color: .labelColor))
            row.addArrangedSubview(Panel.spacer())
            let parent = NSPopUpButton()
            parent.controlSize = .small
            parent.font = .systemFont(ofSize: 12)
            parent.addItem(withTitle: Bucket.personal.name)
            parent.lastItem?.representedObject = Bucket.personal.id
            parent.addItem(withTitle: Bucket.work.name)
            parent.lastItem?.representedObject = Bucket.work.id
            if let item = parent.menu?.items.first(where: { ($0.representedObject as? String) == bucket.parent }) {
                parent.select(item)
            }
            parent.target = self
            parent.action = #selector(parentPicked(_:))
            parent.identifier = NSUserInterfaceItemIdentifier(bucket.id)
            parent.widthAnchor.constraint(equalToConstant: 110).isActive = true
            let remove = NSButton(title: "Remove", target: self, action: #selector(removeBucket(_:)))
            remove.identifier = NSUserInterfaceItemIdentifier(bucket.id)
            remove.bezelStyle = .shadowlessSquare
            remove.isBordered = false
            remove.font = .systemFont(ofSize: 12)
            row.addArrangedSubview(parent)
            row.addArrangedSubview(remove)
            customStack.addArrangedSubview(row)
            Panel.pin(row, to: customStack)
        }
    }

    private func renderMenu(_ rollup: DayRollup?) {
        let ids = ["__total", Bucket.work.id] + model.preferences.buckets.map(\.id)
        if ids != bucketToggleOrder {
            bucketToggleOrder = ids
            rebuildBucketToggles()
        }
        bucketToggles["__total"]?.state = model.preferences.showTotal ? .on : .off
        for bucket in [Bucket.work] + model.preferences.buckets {
            bucketToggles[bucket.id]?.state = model.preferences.menuBucketIds.contains(bucket.id) ? .on : .off
        }
        deviceToggle.state = model.preferences.showDevices ? .on : .off
        deviceFieldStack.isHidden = !model.preferences.showDevices
        ensureDeviceFields(rollup?.devices ?? [])
        previewLabel.stringValue = model.menuTitle
        if view.window?.firstResponder !== sshField.currentEditor(), sshField.stringValue != model.preferences.ssh {
            sshField.stringValue = model.preferences.ssh
        }
        let draft = sshField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        if SSHTarget.isValid(draft) {
            sshHint.isHidden = true
        } else {
            sshHint.isHidden = false
            sshHint.stringValue = "Use a host like pact@pact-host, then press Return."
        }
    }

    private func rebuildBucketToggles() {
        guard let total = bucketToggles["__total"] else { return }
        for (id, toggle) in bucketToggles where id != "__total" {
            menuBody.removeArrangedSubview(toggle)
            toggle.removeFromSuperview()
        }
        bucketToggles = ["__total": total]
        guard let anchor = menuBody.arrangedSubviews.firstIndex(where: { $0 === total }) else { return }
        var insert = anchor + 1
        for bucket in [Bucket.work] + model.preferences.buckets {
            let toggle = NSButton(checkboxWithTitle: bucket.name, target: self, action: #selector(bucketMenuToggled(_:)))
            toggle.identifier = NSUserInterfaceItemIdentifier(bucket.id)
            menuBody.insertArrangedSubview(toggle, at: insert)
            bucketToggles[bucket.id] = toggle
            insert += 1
        }
    }

    private func ensureDeviceFields(_ devices: [DeviceTotal]) {
        let order = devices.map(\.id)
        if order != deviceFieldOrder {
            deviceFieldOrder = order
            Panel.clear(deviceFieldStack)
            deviceFields = [:]
            for device in devices {
                let row = Panel.hstack(8)
                let name = Panel.caption(device.name, size: 11, weight: .regular, color: .secondaryLabelColor)
                let field = NSTextField()
                field.placeholderString = shortDeviceName(device.name, override: nil)
                field.stringValue = model.preferences.deviceLabels[device.deviceId] ?? ""
                field.delegate = self
                field.bezelStyle = .roundedBezel
                field.font = .systemFont(ofSize: 12)
                field.identifier = NSUserInterfaceItemIdentifier("device:" + device.deviceId)
                field.widthAnchor.constraint(equalToConstant: 96).isActive = true
                row.addArrangedSubview(name)
                row.addArrangedSubview(Panel.spacer())
                row.addArrangedSubview(field)
                deviceFieldStack.addArrangedSubview(row)
                Panel.pin(row, to: deviceFieldStack)
                deviceFields[device.deviceId] = field
            }
            return
        }
        for device in devices {
            guard let field = deviceFields[device.deviceId] else { continue }
            let value = model.preferences.deviceLabels[device.deviceId] ?? ""
            if view.window?.firstResponder !== field.currentEditor(), field.stringValue != value {
                field.stringValue = value
            }
        }
    }

    func controlTextDidChange(_ obj: Notification) {
        guard let field = obj.object as? NSTextField else { return }
        if field === searchField {
            query = field.stringValue
            renderCategoryEditor(model.dayRollup)
            return
        }
        if field === sshField {
            let draft = field.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
            sshHint.isHidden = SSHTarget.isValid(draft)
            sshHint.stringValue = sshHint.isHidden ? "" : "Use a host like pact@pact-host, then press Return."
            return
        }
        if field === bucketField { return }
        if let id = field.identifier?.rawValue, id.hasPrefix("device:") {
            model.setDeviceLabel(String(id.dropFirst("device:".count)), field.stringValue)
        }
    }

    func controlTextDidEndEditing(_ obj: Notification) {
        guard let field = obj.object as? NSTextField else { return }
        if field === sshField { model.setSSH(field.stringValue) }
        if field === bucketField { addBucket() }
    }

    @objc private func previousDay() { model.shiftDay(-1) }
    @objc private func nextDay() { model.shiftDay(1) }
    @objc private func refresh() { model.refresh() }
    @objc private func quit() { NSApp.terminate(nil) }
    @objc private func toggleSettings() {
        showingSettings.toggle()
        render()
        scrollToTop()
    }

    @objc private func closeSettings() {
        showingSettings = false
        render()
        scrollToTop()
    }

    private func scrollToTop() {
        guard let scrollView else { return }
        scrollView.contentView.scroll(to: .zero)
        scrollView.reflectScrolledClipView(scrollView.contentView)
    }

    @objc private func loginToggled() { model.setOpenAtLogin(loginToggle.state == .on) }
    @objc private func totalToggled(_ sender: NSButton) { model.setShowTotal(sender.state == .on) }
    @objc private func devicesToggled(_ sender: NSButton) { model.setShowDevices(sender.state == .on) }

    @objc private func bucketMenuToggled(_ sender: NSButton) {
        guard let id = sender.identifier?.rawValue else { return }
        model.setMenuBucket(id, on: sender.state == .on)
    }

    @objc private func filterTapped(_ sender: NSButton) {
        filter = sender.identifier?.rawValue ?? "all"
        render()
    }

    @objc private func bucketPicked(_ sender: NSPopUpButton) {
        guard let category = sender.identifier?.rawValue else { return }
        guard let id = sender.selectedItem?.representedObject as? String else { return }
        if id == Bucket.unassigned.id {
            model.resetAssignment(category)
            return
        }
        model.setAssignment(category: category, bucketId: id)
    }

    @objc private func resetMark(_ sender: NSButton) {
        guard let category = sender.identifier?.rawValue else { return }
        model.resetAssignment(category)
    }

    @objc private func parentPicked(_ sender: NSPopUpButton) {
        guard let id = sender.identifier?.rawValue else { return }
        guard let parent = sender.selectedItem?.representedObject as? String else { return }
        model.setBucketParent(id, parent)
    }

    @objc private func removeBucket(_ sender: NSButton) {
        guard let id = sender.identifier?.rawValue else { return }
        model.removeBucket(id)
    }

    @objc private func addBucket() {
        let parent = bucketParent.selectedItem?.representedObject as? String ?? Bucket.personal.id
        guard model.addBucket(name: bucketField.stringValue, parent: parent) else { return }
        bucketField.stringValue = ""
    }
}

private func deviceSymbol(platform: String, name: String) -> String {
    let text = (platform + " " + name).lowercased()
    if text.contains("ipad") || text.contains("tablet") { return "ipad" }
    if text.contains("phone") || text.contains("android") || text.contains("iphone") || text == "ios" || text.contains(" ios") {
        return "iphone"
    }
    return "laptopcomputer"
}

@MainActor
private enum Panel {
    static func vstack(_ spacing: CGFloat) -> NSStackView {
        let stack = NSStackView()
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = spacing
        stack.translatesAutoresizingMaskIntoConstraints = false
        return stack
    }

    static func hstack(_ spacing: CGFloat) -> NSStackView {
        let stack = NSStackView()
        stack.orientation = .horizontal
        stack.alignment = .centerY
        stack.spacing = spacing
        stack.translatesAutoresizingMaskIntoConstraints = false
        return stack
    }

    static func caption(_ text: String, size: CGFloat, weight: NSFont.Weight, color: NSColor) -> NSTextField {
        let field = NSTextField(labelWithString: text)
        field.font = .systemFont(ofSize: size, weight: weight)
        field.textColor = color
        field.lineBreakMode = .byTruncatingTail
        field.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return field
    }

    static func wrapping(_ text: String) -> NSTextField {
        let field = NSTextField(wrappingLabelWithString: text)
        field.font = .systemFont(ofSize: 12)
        field.textColor = .secondaryLabelColor
        field.maximumNumberOfLines = 0
        field.preferredMaxLayoutWidth = 440
        return field
    }

    static func icon(_ symbol: String, tip: String) -> NSButton {
        let button = NSButton()
        button.image = NSImage(systemSymbolName: symbol, accessibilityDescription: tip)
        button.bezelStyle = .shadowlessSquare
        button.isBordered = false
        button.toolTip = tip
        button.imageScaling = .scaleProportionallyDown
        button.widthAnchor.constraint(equalToConstant: 22).isActive = true
        button.heightAnchor.constraint(equalToConstant: 22).isActive = true
        return button
    }

    static func spacer() -> NSView {
        let view = NSView()
        view.setContentHuggingPriority(.defaultLow, for: .horizontal)
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return view
    }

    static func gap(_ width: CGFloat) -> NSView {
        let view = NSView()
        view.translatesAutoresizingMaskIntoConstraints = false
        view.widthAnchor.constraint(equalToConstant: width).isActive = true
        return view
    }

    static func hairline() -> NSView {
        let view = NSView()
        view.translatesAutoresizingMaskIntoConstraints = false
        view.wantsLayer = true
        view.layer?.backgroundColor = NSColor.separatorColor.cgColor
        view.heightAnchor.constraint(equalToConstant: 1).isActive = true
        return view
    }

    static func pin(_ view: NSView, to stack: NSStackView) {
        view.translatesAutoresizingMaskIntoConstraints = false
        view.widthAnchor.constraint(equalTo: stack.widthAnchor).isActive = true
    }

    static func clear(_ stack: NSStackView) {
        for view in stack.arrangedSubviews {
            stack.removeArrangedSubview(view)
            view.removeFromSuperview()
        }
    }
}

private final class FlippedView: NSView {
    override var isFlipped: Bool { true }
}

private final class ChoiceControl: NSView {
    var titles: [String]
    var selected = 0 { didSet { needsDisplay = true } }
    var onSelect: ((Int) -> Void)?

    init(titles: [String]) {
        self.titles = titles
        super.init(frame: .zero)
        translatesAutoresizingMaskIntoConstraints = false
        heightAnchor.constraint(equalToConstant: 28).isActive = true
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    override func draw(_ dirtyRect: NSRect) {
        guard !titles.isEmpty, bounds.width > 0 else { return }
        let gap: CGFloat = 6
        let width = (bounds.width - gap * CGFloat(titles.count - 1)) / CGFloat(titles.count)
        for (index, title) in titles.enumerated() {
            let rect = NSRect(x: CGFloat(index) * (width + gap), y: 1, width: width, height: bounds.height - 2)
            let on = index == selected
            let fill = on
                ? NSColor(srgbRed: 0.27, green: 0.51, blue: 0.96, alpha: 1)
                : NSColor(srgbRed: 0.28, green: 0.29, blue: 0.32, alpha: 1)
            fill.setFill()
            NSBezierPath(roundedRect: rect, xRadius: 7, yRadius: 7).fill()
            let style = NSMutableParagraphStyle()
            style.alignment = .center
            let attrs: [NSAttributedString.Key: Any] = [
                .font: NSFont.systemFont(ofSize: 12, weight: on ? .semibold : .regular),
                .foregroundColor: NSColor.white,
                .paragraphStyle: style,
            ]
            let text = title as NSString
            let size = text.size(withAttributes: attrs)
            let textRect = NSRect(x: rect.minX, y: rect.midY - size.height / 2, width: rect.width, height: size.height)
            text.draw(in: textRect, withAttributes: attrs)
        }
    }

    override func mouseDown(with event: NSEvent) {
        let point = convert(event.locationInWindow, from: nil)
        guard bounds.width > 0, !titles.isEmpty else { return }
        let index = min(titles.count - 1, max(0, Int(point.x / bounds.width * CGFloat(titles.count))))
        selected = index
        onSelect?(index)
    }
}

private final class DotView: NSView {
    var color: NSColor
    init(color: NSColor) {
        self.color = color
        super.init(frame: .zero)
        translatesAutoresizingMaskIntoConstraints = false
        widthAnchor.constraint(equalToConstant: 8).isActive = true
        heightAnchor.constraint(equalToConstant: 8).isActive = true
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    override func draw(_ dirtyRect: NSRect) {
        color.setFill()
        NSBezierPath(ovalIn: bounds).fill()
    }
}

private final class StackedBarView: NSView {
    var parts: [BucketTotal]
    init(parts: [BucketTotal]) {
        self.parts = parts
        super.init(frame: .zero)
        translatesAutoresizingMaskIntoConstraints = false
        heightAnchor.constraint(equalToConstant: 10).isActive = true
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    override func draw(_ dirtyRect: NSRect) {
        let total = parts.reduce(0) { $0 + $1.seconds }
        let path = NSBezierPath(roundedRect: bounds, xRadius: bounds.height / 2, yRadius: bounds.height / 2)
        path.addClip()
        NSColor.labelColor.withAlphaComponent(0.08).setFill()
        bounds.fill()
        guard total > 0 else { return }
        var x: CGFloat = 0
        for part in parts {
            let width = bounds.width * CGFloat(part.seconds / total)
            BucketPalette.color(part.id).setFill()
            NSRect(x: x, y: 0, width: width, height: bounds.height).fill()
            x += width
        }
    }
}

private final class MeterView: NSView {
    var fraction: Double
    var fill: NSColor
    init(fraction: Double, fill: NSColor) {
        self.fraction = fraction
        self.fill = fill
        super.init(frame: .zero)
        translatesAutoresizingMaskIntoConstraints = false
        heightAnchor.constraint(equalToConstant: 8).isActive = true
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    override func draw(_ dirtyRect: NSRect) {
        let radius = bounds.height / 2
        NSColor.labelColor.withAlphaComponent(0.08).setFill()
        NSBezierPath(roundedRect: bounds, xRadius: radius, yRadius: radius).fill()
        let width = bounds.width * CGFloat(min(max(fraction, 0), 1))
        guard width > 0 else { return }
        fill.setFill()
        NSBezierPath(roundedRect: NSRect(x: 0, y: 0, width: width, height: bounds.height), xRadius: radius, yRadius: radius).fill()
    }
}

private enum Disclosure {
    case none
    case closed
    case open
}

private final class ClickRow: NSView {
    var onClick: (() -> Void)?

    override func hitTest(_ point: NSPoint) -> NSView? {
        guard !isHidden else { return nil }
        let local = convert(point, from: superview)
        return bounds.contains(local) ? self : nil
    }

    override func mouseDown(with event: NSEvent) {
        // Rebuild the rows after this click returns. Removing the hit view mid-event crashes.
        let action = onClick
        DispatchQueue.main.async {
            action?()
        }
    }

    override func resetCursorRects() {
        discardCursorRects()
        addCursorRect(bounds, cursor: .pointingHand)
    }
}

private final class HourChartView: NSView {
    var hours: [HourTotal] = [] { didSet { needsDisplay = true } }

    override var intrinsicContentSize: NSSize {
        NSSize(width: NSView.noIntrinsicMetric, height: 150)
    }

    override func draw(_ dirtyRect: NSRect) {
        let labelHeight: CGFloat = 18
        let plot = NSRect(x: 0, y: labelHeight, width: bounds.width, height: max(0, bounds.height - labelHeight))
        guard !hours.isEmpty, plot.width > 0 else { return }
        let gap: CGFloat = 3
        let column = (plot.width - gap * CGFloat(hours.count - 1)) / CGFloat(hours.count)
        let peak = max(hours.map(\.seconds).max() ?? 0, 60)
        let attrs: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: 9),
            .foregroundColor: NSColor.secondaryLabelColor,
        ]
        for (index, hour) in hours.enumerated() {
            let x = CGFloat(index) * (column + gap)
            if hour.seconds > 0 {
                var y = plot.minY
                for bucket in hour.buckets {
                    let height = plot.height * CGFloat(bucket.seconds / peak)
                    BucketPalette.color(bucket.id).setFill()
                    NSRect(x: x, y: y, width: max(column, 1), height: height).fill()
                    y += height
                }
            }
            if hour.hour % 6 == 0 {
                let text = "\(hour.hour)" as NSString
                let size = text.size(withAttributes: attrs)
                text.draw(at: NSPoint(x: x + (column - size.width) / 2, y: 1), withAttributes: attrs)
            }
        }
    }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        for area in trackingAreas { removeTrackingArea(area) }
        addTrackingArea(NSTrackingArea(
            rect: bounds,
            options: [.mouseMoved, .activeAlways, .inVisibleRect],
            owner: self
        ))
    }

    override func mouseMoved(with event: NSEvent) {
        let point = convert(event.locationInWindow, from: nil)
        guard !hours.isEmpty, bounds.width > 0 else { return }
        let index = min(hours.count - 1, max(0, Int(point.x / bounds.width * CGFloat(hours.count))))
        let hour = hours[index]
        if hour.buckets.isEmpty {
            toolTip = "\(hour.hour):00"
        } else {
            let parts = hour.buckets.map { "\($0.name) \(formatDuration($0.seconds))" }
            toolTip = "\(hour.hour):00 · " + parts.joined(separator: ", ")
        }
    }
}
