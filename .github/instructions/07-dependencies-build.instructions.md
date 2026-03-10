# Dependencies and Build Instructions

applyTo: "**/build.gradle,**/gradle.properties,**/Ballerina.toml"

---

## Version Requirements

| Dependency | Version | Notes |
|------------|---------|-------|
| Ballerina | 2201.13.0 | Swan Lake Update 13 |
| Java | 21 | Required for Temporal SDK 1.32.0 |
| Temporal SDK | 1.32.0 | io.temporal:temporal-sdk |
| gRPC | 1.68.2 | Must match Temporal SDK version |
| Gradle | 8.x | Wrapper included (gradlew) |

## Current Implementation

### 1. Module Structure

```
module-ballerina-workflow/
├── ballerina/              # Core Ballerina module (types, annotations, API)
│   └── modules/internal/   # Internal registration (registerWorkflow)
├── native/                 # Java native implementation (Temporal integration)
├── compiler-plugin/        # @Workflow/@Activity validation plugin
├── compiler-plugin-tests/  # Compiler plugin test suite
├── integration-tests/      # Full workflow integration tests
├── build-config/           # Shared build configuration (checkstyle, spotbugs)
├── build.gradle            # Root build configuration (incl. Temporal CLI dev server)
├── settings.gradle         # Multi-module project settings
└── gradle.properties       # Version properties
```

### 2. Version Configuration

All dependency versions are in [gradle.properties](gradle.properties). Key entries:
- `version` — module version (e.g., `0.2.1-SNAPSHOT`)
- `ballerinaLangVersion` — Ballerina distribution version
- `temporalSdkVersion`, `grpcVersion`, `jacksonVersion` — Temporal and related dependencies
- `stdlibHttpVersion`, `stdlibIoVersion`, etc. — Ballerina standard library dependencies

### 3. Module Build Configurations

#### Root [build.gradle](build.gradle)
- Plugins: `com.github.spotbugs`, `net.researchgate.release`, `jacoco`
- `allprojects`: applies `maven-publish`, configures repositories (mavenLocal, WSO2 nexus, Maven Central, GitHub Packages)
- Contains Temporal CLI dev server management tasks — see [06-integration-testing.instructions.md](.github/instructions/06-integration-testing.instructions.md)
- `release` block with `failOnPublishNeeded=false`, `tagTemplate='v${version}'`

#### [native/build.gradle](native/build.gradle)
- Plugins: `java`, `checkstyle`, `com.github.spotbugs`
- Dependencies: `ballerina-runtime`, `temporal-sdk`, `temporal-testing`, `jackson`, `grpc-*`
- Publishing: `workflow-native` jar to GitHub Packages

#### [compiler-plugin/build.gradle](compiler-plugin/build.gradle)
- Plugins: `java`, `checkstyle`, `com.github.spotbugs`
- Dependencies: `ballerina-lang`, `ballerina-tools-api`, `ballerina-parser`
- Publishing: `workflow-compiler-plugin` jar to GitHub Packages

#### [ballerina/build.gradle](ballerina/build.gradle)
- Plugin: `io.ballerina.plugin`
- Publishing: bala zip artifact to GitHub Packages
- `updateTomlFiles` task substitutes version placeholders in `Ballerina.toml` and `CompilerPlugin.toml`

#### [integration-tests/build.gradle](integration-tests/build.gradle)
- Uses distribution-based testing via `io.ballerina.plugin`
- Sets `BALLERINA_DEV_COMPILE_BALLERINA_ORG=true` environment variable
- Depends on `:startSharedTestServer` for Temporal CLI dev server
- Parses `test_results.json` and fails build on test failures

### 4. Build Commands

```bash
# Full build
./gradlew clean build

# Build without tests
./gradlew build -x test

# Module-specific builds
./gradlew :workflow-native:build
./gradlew :workflow-compiler-plugin:build
./gradlew :workflow-ballerina:build

# Tests
./gradlew test                                    # All tests
./gradlew :workflow-ballerina:test                 # Unit tests (no Temporal server)
./gradlew :workflow-integration-tests:test         # Integration tests (with Temporal CLI dev server)
./gradlew :workflow-compiler-plugin-tests:test     # Compiler plugin tests

# Publishing
./gradlew :workflow-ballerina:updateTomlFiles
./gradlew publishToMavenLocal
```

### 5. Dependency Tree
```text
workflow-ballerina
├── workflow-native.jar
│   ├── ballerina-runtime (2201.13.0)
│   ├── temporal-sdk (1.32.0)
│   │   └── grpc-netty-shaded (1.68.2)
│   └── temporal-testing (1.32.0)
└── stdlib dependencies (via Dependencies.toml)

workflow-compiler-plugin.jar
└── ballerina-tools-api (2201.13.0)
```

## Troubleshooting

### Common Issues

**Version mismatch errors:**
```bash
./gradlew clean
rm -rf ballerina/target ballerina/build
./gradlew build
```

**Temporal SDK compatibility:**
```bash
./gradlew dependencies | grep grpc
./gradlew dependencies | grep temporal
```

**Test server won't start:**
```bash
lsof -i :7233        # Check port availability
which temporal        # Check if temporal CLI is installed
brew install temporal  # Install on macOS
```
