import AppKit
import Observation
import PactScreenCore

enum BucketPalette {
    static func color(_ id: String) -> NSColor {
        switch id {
        case "work":
            return NSColor(srgbRed: 0.36, green: 0.56, blue: 0.95, alpha: 1)
        case "entertainment":
            return NSColor(srgbRed: 0.95, green: 0.57, blue: 0.24, alpha: 1)
        case "gaming":
            return NSColor(srgbRed: 0.64, green: 0.47, blue: 0.96, alpha: 1)
        case "communication":
            return NSColor(srgbRed: 0.20, green: 0.70, blue: 0.55, alpha: 1)
        case "other":
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
    private let model: AppModel
    private var page = 0
    private var chart = 0
    private var filter = "all"
    private var query = ""
    private var showMenu = false
    private var deviceFieldOrder: [String] = []
    private var deviceFields: [String: NSTextField] = [:]
    private var bucketToggleOrder: [String] = []

    private let dateLabel = Panel.caption("", size: 14, weight: .semibold, color: .labelColor)
    private let freshnessLabel = Panel.caption("", size: 11, weight: .regular, color: .secondaryLabelColor)
    private let nextButton = Panel.icon("chevron.right", tip: "Next day")
    private let totalLabel = Panel.caption("", size: 34, weight: .semibold, color: .labelColor)
    private let countLabel = Panel.caption("", size: 11, weight: .regular, color: .secondaryLabelColor)
    private let emptyLabel = Panel.wrapping("")
    private let pageChoice = ChoiceControl(titles: ["Today", "Categories"])
    private let todayStack = Panel.vstack(10)
    private let categoriesStack = Panel.vstack(12)
    private let menuStack = Panel.vstack(8)
    private let menuBody = Panel.vstack(8)
    private let legendStack = Panel.vstack(8)
    private let deviceStack = Panel.vstack(6)
    private let chartChoice = ChoiceControl(titles: ["Hours", "Apps", "Devices"])
    private let chartCaption = Panel.wrapping("")
    private let chartHost = NSView()
    private let hourChart = HourChartView()
    private let filterStack = Panel.hstack(6)
    private let rowsStack = Panel.vstack(0)
    private let customStack = Panel.vstack(6)
    private let previewLabel = Panel.caption("", size: 13, weight: .medium, color: .labelColor)
    private let searchField = NSTextField()
    private let bucketField = NSTextField()
    private let sshField = NSTextField()
    private let sshHint = Panel.caption("", size: 11, weight: .regular, color: .systemOrange)
    private let loginNote = Panel.wrapping("")
    private let loginToggle = NSButton(checkboxWithTitle: "Open at login", target: nil, action: nil)
    private let refreshButton = Panel.icon("arrow.clockwise", tip: "Fetch this day from the VPS")
    private let menuToggle = NSButton()
    private var bucketToggles: [String: NSButton] = [:]
    private let deviceToggle = NSButton(checkboxWithTitle: "Each device", target: nil, action: nil)
    private let deviceFieldStack = Panel.vstack(6)

    init(model: AppModel) {
        self.model = model
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    override func loadView() {
        let root = NSView(frame: NSRect(x: 0, y: 0, width: 400, height: 640))
        view = root
        let header = makeHeader()
        let scroll = makeScroll()
        let footer = makeFooter()
        let topLine = Panel.hairline()
        let bottomLine = Panel.hairline()
        root.addSubview(header)
        root.addSubview(topLine)
        root.addSubview(scroll)
        root.addSubview(bottomLine)
        root.addSubview(footer)
        NSLayoutConstraint.activate([
            header.topAnchor.constraint(equalTo: root.topAnchor),
            header.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            header.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            topLine.topAnchor.constraint(equalTo: header.bottomAnchor),
            topLine.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            topLine.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            footer.bottomAnchor.constraint(equalTo: root.bottomAnchor, constant: -8),
            footer.leadingAnchor.constraint(equalTo: root.leadingAnchor, constant: 16),
            footer.trailingAnchor.constraint(equalTo: root.trailingAnchor, constant: -16),
            bottomLine.bottomAnchor.constraint(equalTo: footer.topAnchor),
            bottomLine.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            bottomLine.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            scroll.topAnchor.constraint(equalTo: topLine.bottomAnchor),
            scroll.bottomAnchor.constraint(equalTo: bottomLine.topAnchor),
            scroll.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: root.trailingAnchor),
        ])
        wire()
        render()
        watch()
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
        for item in [previous, titles, nextButton] {
            item.translatesAutoresizingMaskIntoConstraints = false
            row.addSubview(item)
        }
        NSLayoutConstraint.activate([
            row.heightAnchor.constraint(equalToConstant: 52),
            previous.leadingAnchor.constraint(equalTo: row.leadingAnchor, constant: 12),
            previous.centerYAnchor.constraint(equalTo: row.centerYAnchor),
            nextButton.trailingAnchor.constraint(equalTo: row.trailingAnchor, constant: -12),
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
        pageChoice.onSelect = { [weak self] index in
            self?.page = index
            self?.render()
        }
        chartChoice.onSelect = { [weak self] index in
            self?.chart = index
            self?.render()
        }
        chartHost.translatesAutoresizingMaskIntoConstraints = false
        chartHost.setContentHuggingPriority(.defaultLow, for: .horizontal)
        hourChart.translatesAutoresizingMaskIntoConstraints = false

        let deviceTitle = Panel.caption("Devices", size: 11, weight: .semibold, color: .secondaryLabelColor)
        todayStack.addArrangedSubview(legendStack)
        todayStack.addArrangedSubview(deviceTitle)
        todayStack.addArrangedSubview(deviceStack)
        todayStack.addArrangedSubview(chartChoice)
        todayStack.addArrangedSubview(chartCaption)
        todayStack.addArrangedSubview(chartHost)
        Panel.pin(chartChoice, to: todayStack)
        Panel.pin(chartHost, to: todayStack)
        Panel.pin(legendStack, to: todayStack)
        Panel.pin(deviceStack, to: todayStack)
        hourChart.heightAnchor.constraint(equalToConstant: 128).isActive = true

        let intro = Panel.wrapping("YouTube, JioHotstar, and Netflix start as Entertainment. The terminal, Teams, and other websites start as Work. Change any row that doesn't fit. Marks stay on this Mac.")
        searchField.placeholderString = "Search"
        searchField.delegate = self
        searchField.bezelStyle = .roundedBezel
        searchField.font = .systemFont(ofSize: 13)
        bucketField.placeholderString = "New bucket"
        bucketField.delegate = self
        bucketField.bezelStyle = .roundedBezel
        bucketField.font = .systemFont(ofSize: 13)
        let add = NSButton(title: "Add", target: self, action: #selector(addBucket))
        add.bezelStyle = .rounded
        let addRow = Panel.hstack(8)
        addRow.addArrangedSubview(bucketField)
        addRow.addArrangedSubview(add)
        bucketField.setContentHuggingPriority(.defaultLow, for: .horizontal)
        categoriesStack.addArrangedSubview(intro)
        categoriesStack.addArrangedSubview(searchField)
        categoriesStack.addArrangedSubview(filterStack)
        categoriesStack.addArrangedSubview(rowsStack)
        categoriesStack.addArrangedSubview(customStack)
        categoriesStack.addArrangedSubview(addRow)
        Panel.pin(intro, to: categoriesStack)
        Panel.pin(searchField, to: categoriesStack)
        Panel.pin(filterStack, to: categoriesStack)
        Panel.pin(rowsStack, to: categoriesStack)
        Panel.pin(customStack, to: categoriesStack)
        Panel.pin(addRow, to: categoriesStack)

        menuToggle.title = "Menu bar"
        menuToggle.bezelStyle = .shadowlessSquare
        menuToggle.isBordered = false
        menuToggle.font = .systemFont(ofSize: 13, weight: .semibold)
        menuToggle.contentTintColor = .secondaryLabelColor
        menuToggle.target = self
        menuToggle.action = #selector(toggleMenu)
        menuToggle.alignment = .left
        buildMenuBody()
        menuStack.addArrangedSubview(menuToggle)
        menuStack.addArrangedSubview(menuBody)
        Panel.pin(menuBody, to: menuStack)

        content.addArrangedSubview(emptyLabel)
        content.addArrangedSubview(totalLabel)
        content.addArrangedSubview(countLabel)
        content.addArrangedSubview(pageChoice)
        content.addArrangedSubview(todayStack)
        content.addArrangedSubview(categoriesStack)
        content.addArrangedSubview(menuStack)
        for item in [emptyLabel, pageChoice, todayStack, categoriesStack, menuStack] {
            Panel.pin(item, to: content)
        }
    }

    private func buildMenuBody() {
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
        let vps = Panel.caption("VPS", size: 12, weight: .regular, color: .secondaryLabelColor)
        sshRow.addArrangedSubview(vps)
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
    }

    private func makeFooter() -> NSView {
        refreshButton.target = self
        refreshButton.action = #selector(refresh)
        loginToggle.target = self
        loginToggle.action = #selector(loginToggled)
        loginToggle.font = .systemFont(ofSize: 12)
        let quit = NSButton(title: "Quit", target: self, action: #selector(quit))
        quit.bezelStyle = .shadowlessSquare
        quit.isBordered = false
        quit.font = .systemFont(ofSize: 12)
        quit.contentTintColor = .secondaryLabelColor
        let row = Panel.hstack(8)
        row.addArrangedSubview(refreshButton)
        row.addArrangedSubview(Panel.spacer())
        row.addArrangedSubview(loginToggle)
        row.addArrangedSubview(quit)
        let column = Panel.vstack(6)
        column.setContentHuggingPriority(.required, for: .vertical)
        column.setContentCompressionResistancePriority(.required, for: .vertical)
        column.addArrangedSubview(loginNote)
        column.addArrangedSubview(row)
        Panel.pin(loginNote, to: column)
        Panel.pin(row, to: column)
        return column
    }

    private func wire() {
        sshField.stringValue = model.preferences.ssh
    }

    private func render() {
        let day = model.day.isEmpty ? DayClock.today() : model.day
        dateLabel.stringValue = formatDay(day)
        freshnessLabel.stringValue = model.freshness
        nextButton.isEnabled = day != DayClock.today()
        refreshButton.isEnabled = !model.refreshing
        loginToggle.state = model.preferences.openAtLogin ? .on : .off
        loginNote.stringValue = model.loginNote ?? ""
        loginNote.isHidden = model.loginNote == nil
        previewLabel.stringValue = model.menuTitle
        pageChoice.selected = page
        chartChoice.selected = chart
        menuBody.isHidden = !showMenu
        menuToggle.title = showMenu ? "Menu bar  ▾" : "Menu bar  ▸"

        let rollup = model.dayRollup
        let hasTime = (rollup?.screenSeconds ?? 0) > 0
        emptyLabel.isHidden = hasTime
        totalLabel.isHidden = !hasTime
        countLabel.isHidden = !hasTime
        pageChoice.isHidden = !hasTime
        todayStack.isHidden = !hasTime || page != 0
        categoriesStack.isHidden = !hasTime || page != 1
        menuStack.isHidden = !hasTime
        if !hasTime {
            if model.refreshing, rollup == nil {
                emptyLabel.stringValue = "Fetching from the VPS…"
            } else if let error = model.errorText, rollup == nil {
                emptyLabel.stringValue = error
            } else {
                emptyLabel.stringValue = "Nothing has been shipped for this day. Collectors run every 5 minutes."
            }
            return
        }
        guard let rollup else { return }
        totalLabel.stringValue = formatDuration(rollup.screenSeconds)
        let count = rollup.devices.filter { $0.seconds > 0 }.count
        countLabel.stringValue = count == 1 ? "1 device" : "\(count) devices"
        renderLegend(rollup)
        renderDevices(rollup)
        renderChart(rollup)
        renderCategories(rollup)
        renderMenu(rollup)
    }

    private func renderLegend(_ rollup: DayRollup) {
        Panel.clear(legendStack)
        legendStack.addArrangedSubview(StackedBarView(parts: rollup.buckets))
        Panel.pin(legendStack.arrangedSubviews[0], to: legendStack)
        let whole = rollup.buckets.reduce(0) { $0 + $1.seconds }
        for bucket in rollup.buckets {
            legendStack.addArrangedSubview(bucketRow(bucket, whole: whole))
        }
    }

    private func bucketRow(_ bucket: BucketTotal, whole: Double) -> NSView {
        let row = Panel.hstack(8)
        let dot = DotView(color: BucketPalette.color(bucket.id))
        let name = Panel.caption(bucket.name, size: 13, weight: .regular, color: .labelColor)
        let time = Panel.caption(formatDuration(bucket.seconds), size: 13, weight: .regular, color: .labelColor)
        time.font = .monospacedDigitSystemFont(ofSize: 13, weight: .regular)
        time.alignment = .right
        let percent = Panel.caption(percentText(bucket.seconds, of: whole), size: 12, weight: .regular, color: .secondaryLabelColor)
        percent.font = .monospacedDigitSystemFont(ofSize: 12, weight: .regular)
        percent.alignment = .right
        percent.widthAnchor.constraint(equalToConstant: 40).isActive = true
        row.addArrangedSubview(dot)
        row.addArrangedSubview(name)
        row.addArrangedSubview(Panel.spacer())
        row.addArrangedSubview(time)
        row.addArrangedSubview(percent)
        time.setContentCompressionResistancePriority(.required, for: .horizontal)
        return row
    }

    private func renderDevices(_ rollup: DayRollup) {
        Panel.clear(deviceStack)
        for device in rollup.devices {
            let row = Panel.hstack(8)
            let name = Panel.caption(
                preferredDeviceName(device.name, override: model.preferences.deviceLabels[device.deviceId]),
                size: 13,
                weight: .regular,
                color: .labelColor
            )
            let time = Panel.caption(formatDuration(device.seconds), size: 13, weight: .regular, color: .labelColor)
            time.font = .monospacedDigitSystemFont(ofSize: 13, weight: .regular)
            row.addArrangedSubview(name)
            row.addArrangedSubview(Panel.spacer())
            row.addArrangedSubview(time)
            time.setContentCompressionResistancePriority(.required, for: .horizontal)
            deviceStack.addArrangedSubview(row)
            Panel.pin(row, to: deviceStack)
        }
    }

    private func renderChart(_ rollup: DayRollup) {
        chartCaption.stringValue = [
            "Hour by hour, Kolkata time. Colors are the buckets above.",
            "The program in front of you. A browser includes every site opened in it.",
            "Each device, split into the same buckets.",
        ][chart]
        for view in chartHost.subviews { view.removeFromSuperview() }
        let child: NSView
        switch chart {
        case 1:
            child = appChart(rollup.apps)
        case 2:
            child = deviceChart(rollup)
        default:
            if rollup.hours.allSatisfy({ $0.seconds <= 0 }) {
                child = Panel.wrapping("No hour-by-hour record for this day.")
            } else {
                hourChart.hours = rollup.hours
                child = hourChart
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

    private func deviceChart(_ rollup: DayRollup) -> NSView {
        let stack = Panel.vstack(12)
        let peak = rollup.devices.map(\.seconds).max() ?? 1
        for device in rollup.devices {
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
            let bar = StackedBarView(parts: device.buckets)
            let detail = Panel.caption(
                device.buckets.map { "\($0.name) \(formatDuration($0.seconds))" }.joined(separator: " · "),
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

    private func renderCategories(_ rollup: DayRollup) {
        renderFilters()
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let rows = rollup.categories.filter { row in
            if !needle.isEmpty {
                let hay = (row.name + " " + row.category).lowercased()
                if !hay.contains(needle) { return false }
            }
            if filter != "all", row.bucketId != filter { return false }
            return true
        }
        Panel.clear(rowsStack)
        if rows.isEmpty {
            rowsStack.addArrangedSubview(Panel.wrapping("Nothing in this list."))
        }
        for row in rows {
            rowsStack.addArrangedSubview(categoryRow(row))
            Panel.pin(rowsStack.arrangedSubviews.last!, to: rowsStack)
        }
        renderCustomBuckets()
    }

    private func renderFilters() {
        Panel.clear(filterStack)
        var tags: [(String, String)] = [("All", "all")]
        tags += model.preferences.buckets.map { ($0.name, $0.id) }
        tags.append(("Other", Bucket.other.id))
        for (title, id) in tags {
            let button = NSButton(title: title, target: self, action: #selector(filterTapped(_:)))
            button.identifier = NSUserInterfaceItemIdentifier(id)
            button.bezelStyle = .recessed
            button.setButtonType(.momentaryPushIn)
            button.font = .systemFont(ofSize: 11, weight: filter == id ? .semibold : .regular)
            button.contentTintColor = filter == id ? .controlAccentColor : .labelColor
            filterStack.addArrangedSubview(button)
        }
        filterStack.addArrangedSubview(Panel.spacer())
    }

    private func categoryRow(_ row: CategoryRow) -> NSView {
        let line = Panel.hstack(8)
        line.edgeInsets = NSEdgeInsets(top: 6, left: 0, bottom: 6, right: 0)
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
        let choices = model.preferences.buckets + [Bucket.other]
        popup.addItems(withTitles: choices.map(\.name))
        if let index = choices.firstIndex(where: { $0.id == row.bucketId }) {
            popup.selectItem(at: index)
        }
        popup.target = self
        popup.action = #selector(bucketPicked(_:))
        popup.identifier = NSUserInterfaceItemIdentifier(row.category)
        popup.widthAnchor.constraint(equalToConstant: 132).isActive = true
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

    private func renderCustomBuckets() {
        Panel.clear(customStack)
        let custom = model.preferences.buckets.filter { bucket in
            !Bucket.defaults.contains { $0.id == bucket.id }
        }
        guard !custom.isEmpty else { return }
        customStack.addArrangedSubview(Panel.caption("Your buckets", size: 11, weight: .semibold, color: .secondaryLabelColor))
        for bucket in custom {
            let row = Panel.hstack(8)
            row.addArrangedSubview(DotView(color: BucketPalette.color(bucket.id)))
            row.addArrangedSubview(Panel.caption(bucket.name, size: 13, weight: .regular, color: .labelColor))
            row.addArrangedSubview(Panel.spacer())
            let remove = NSButton(title: "Remove", target: self, action: #selector(removeBucket(_:)))
            remove.identifier = NSUserInterfaceItemIdentifier(bucket.id)
            remove.bezelStyle = .shadowlessSquare
            remove.isBordered = false
            remove.font = .systemFont(ofSize: 12)
            row.addArrangedSubview(remove)
            customStack.addArrangedSubview(row)
            Panel.pin(row, to: customStack)
        }
    }

    private func renderMenu(_ rollup: DayRollup) {
        let ids = ["__total"] + model.preferences.buckets.map(\.id) + [Bucket.other.id]
        if ids != bucketToggleOrder {
            bucketToggleOrder = ids
            rebuildBucketToggles()
        }
        bucketToggles["__total"]?.state = model.preferences.showTotal ? .on : .off
        for bucket in model.preferences.buckets {
            bucketToggles[bucket.id]?.state = model.preferences.menuBucketIds.contains(bucket.id) ? .on : .off
        }
        bucketToggles[Bucket.other.id]?.state = model.preferences.menuBucketIds.contains(Bucket.other.id) ? .on : .off
        deviceToggle.state = model.preferences.showDevices ? .on : .off
        deviceFieldStack.isHidden = !model.preferences.showDevices
        ensureDeviceFields(rollup.devices)
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
        for bucket in model.preferences.buckets + [Bucket.other] {
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
            if let rollup = model.dayRollup { renderCategories(rollup) }
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
    @objc private func toggleMenu() { showMenu.toggle(); render() }

    func selectPage(_ index: Int) {
        page = index
        render()
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
        let choices = model.preferences.buckets + [Bucket.other]
        let index = sender.indexOfSelectedItem
        guard choices.indices.contains(index) else { return }
        model.setAssignment(category: category, bucketId: choices[index].id)
    }

    @objc private func resetMark(_ sender: NSButton) {
        guard let category = sender.identifier?.rawValue else { return }
        model.resetAssignment(category)
    }

    @objc private func removeBucket(_ sender: NSButton) {
        guard let id = sender.identifier?.rawValue else { return }
        model.removeBucket(id)
    }

    @objc private func addBucket() {
        guard model.addBucket(name: bucketField.stringValue) else { return }
        bucketField.stringValue = ""
    }
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
        field.preferredMaxLayoutWidth = 360
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
