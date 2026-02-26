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
├── native/                 # Java native implementation (Temporal integration)
├── compiler-plugin/        # @Process/@Activity validation plugin
├── compiler-plugin-tests/  # Compiler plugin test suite
├── integration-tests/      # Full workflow integration tests
├── build-config/           # Shared build configuration (checkstyle, spotbugs)
├── build.gradle            # Root build configuration (incl. Temporal CLI dev server)
├── settings.gradle         # Multi-module project settings
└── gradle.properties       # Version properties
```

### 2. Version Configuration

#### gradle.properties
```properties
# Ballerina
org.gradle.caching=true
group=io.ballerina.stdlib
version=0.2.0-SNAPSHOT
ballerinaLangVersion=2201.13.0

# Temporal
temporalSdkVersion=1.32.0
grpcVersion=1.68.2

# Module metadata
org.gradle.parallel=true
```

### 3. Module Build Configurations

#### Root build.gradle
```groovy
plugins {
    id 'com.github.spotbugs'
    id 'net.researchgate.release'
    id 'jacoco'
}
```
Also contains Temporal CLI dev server management tasks (`startSharedTestServer`, `stopSharedTestServer`) - see 06-integration-testing.instructions.md.
**Prerequisite**: `temporal` CLI must be in PATH for running tests.

#### native/build.gradle
```groovy
plugins {
    id 'java-library'
    id 'checkstyle'
    id 'com.github.spotbugs'
}

dependencies {
    implementation "io.temporal:temporal-sdk:${temporalSdkVersion}"
    implementation "io.grpc:grpc-netty-shaded:${grpcVersion}"
    implementation "io.grpc:grpc-protobuf:${grpcVersion}"
    implementation "io.grpc:grpc-stub:${grpcVersion}"
}
```

#### compiler-plugin/build.gradle
```groovy
plugins {
    id 'java'
}

dependencies {
    implementation "io.ballerina.lang:ballerina-lang:${ballerinaLangVersion}"
    implementation "io.ballerina:ballerina-parser:${ballerinaLangVersion}"
    implementation "io.ballerina:ballerina-tools-api:${ballerinaLangVersion}"
}
```

#### integration-tests/build.gradle
Uses distribution-based testing:
- `BALLERINA_DEV_COMPILE_BALLERINA_ORG=true` environment variable
- `--offline` flag (bala pre-installed in distribution by io.ballerina.plugin)
- Depends on `:startSharedTestServer` for Temporal CLI dev server
- Parses `test_results.json` and fails build on test failures

### 4. Build Commands

#### Full Build
```bash
./gradlew clean build
./gradlew build -x test
```

#### Module-Specific Builds
```bash
./gradlew :workflow-native:build
./gradlew :workflow-compiler-plugin:build
./gradlew :workflow-ballerina:build
```

#### Test Commands
```bash
# Run all tests (unit + integration + compiler plugin)
./gradlew test

# Run unit tests only (no Temporal server)
./gradlew :workflow-ballerina:test

# Run integration tests (with Temporal CLI dev server)
./gradlew :workflow-integration-tests:test

# Run compiler plugin tests
./gradlew :workflow-compiler-plugin-tests:test
```

#### Publishing
```bash
./gradlew :workflow-ballerina:updateTomlFiles
./gradlew publishWorkflowToLocal
```

### 5. Dependency Tree
```
workflow-ballerina
├── workflow-native.jar
│   ├── ballerina-runtime (2201.13.0)
│   ├── temporal-sdk (1.32.0)
│   │   └── grpc-netty-shaded (1.68.2)
│   └── logback (1.2.11)
└── io-ballerina (via Dependencies.toml)

workflow-compiler-plugin.jar
└── ballerina-tools-api (2201.13.0)
```

## Dependency Version Constraints

### Critical Constraints
1. **Temporal SDK 1.32.0 requires compatible gRPC**: Mismatch causes runtime errors
2. **Java 21 required**: Temporal SDK 1.32.0 uses Java 21 features
3. **Ballerina 2201.13.0**: Module built against this version

## Troubleshooting

### Common Issues

**Issue: Version mismatch errors**
```bash
./gradlew clean
rm -rf ballerina/target ballerina/build
./gradlew build
```

**Issue: Temporal SDK compatibility**
```bash
./gradlew dependencies | grep grpc
./gradlew dependencies | grep temporal
```

**Issue: Test server won't start**
```bash
# Check port 7233 is available
lsof -i :7233

# Check if temporal CLI is installed
which temporal

# Install Temporal CLI
# macOS:
brew install temporal
# Linux:
curl -sSf https://temporal.download/cli | sh
```

## Success Criteria

- `./gradlew build` completes successfully
- All modules compile without errors
- Native JAR contains Temporal SDK and dependencies
- Compiler plugin JAR contains Ballerina compiler APIs
- Integration tests pass against Temporal CLI dev server
- Temporal SDK version matches gRPC version
- No dependency conflicts in classpath
