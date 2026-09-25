// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "PactScreen",
    platforms: [
        .macOS(.v14),
    ],
    products: [
        .executable(name: "PactScreen", targets: ["PactScreen"]),
    ],
    targets: [
        .target(name: "PactScreenCore"),
        .executableTarget(
            name: "PactScreen",
            dependencies: ["PactScreenCore"]
        ),
        .executableTarget(
            name: "PactScreenChecks",
            dependencies: ["PactScreenCore"]
        ),
    ]
)
