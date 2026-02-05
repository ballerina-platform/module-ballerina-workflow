# Dependencies and Build Instructions

applyTo: "**/build.gradle,**/gradle.properties,**/Ballerina.toml"

---

## Version Requirements

| Dependency | Version | Notes |
|------------|---------|-------|
| Ballerina | 2201.13.0 | Swan Lake Update 13 |
| Java | 21 | Required for Temporal SDK 1.32.0 |
| Temporal SDK | 1.32.0 | io.temporal:temporal-sdk |
| gRPC | 1.58.1 | Must match Temporal SDK version |
| Gradle | 8.x | Wrapper included (gradlew) |

## Current Implementation

### 1. Module Structure

```
module-ballerina-workflow/
├── ballerina/              # Core Ballerina module (types, annotations, API)
├── native/                 # Java native implementation (Temporal integration)
├── native-test/            # Embedded Temporal test server (shadow JAR)
├── compiler-plugin/        # @Process/@Activity validation plugin
├── compiler-plugin-tests/  # Compiler plugin test suite
├── integration-tests/      # Full workflow integration tests
├── build-config/           # Shared build configuration (checkstyle, spotbugs)
├── build.gradle            # Root build configuration
├── settings.gradle         # Multi-module project settings
└── gradle.properties       # Version properties
```

### 2. Version Configuration

#### gradle.properties
```properties
# Ballerina
org.gradle.jvmargs=-Xmx4g
ballerinaLangVersion=2201.13.0
stdlibIoVersion=1.6.1

# Temporal
temporalSdkVersion=1.32.0
grpcVersion=1.58.1

# Module metadata
group=io.ballerina.stdlib
version=0.1.0-SNAPSHOT

# Build settings
org.gradle.caching=true
org.gradle.parallel=true
```

### 3. Module Build Configurations

#### Root build.gradle
```groovy
plugins {
    id 'com.github.spotbugs' version '5.0.13' apply false
}

allprojects {
    group = project.group
    version = project.version
}

subprojects {
    apply plugin: 'java'
    
    repositories {
        mavenCentral()
        maven {
            url = 'https://maven.pkg.github.com/ballerina-platform/*'
        }
    }
    
    dependencies {
        // Common dependencies
    }
}
```

#### ballerina/build.gradle
```groovy
import org.apache.tools.ant.taskdefs.condition.Os

plugins {
    id 'io.ballerina.plugin'
}

description = 'Ballerina - Workflow Module'

def ballerinaVersion = project.ballerinaLangVersion
def stdlibIoVersion = project.stdlibIoVersion

ballerina {
    packageOrganization = 'ballerina'
    module = 'workflow'
    langVersion = ballerinaVersion
}

dependencies {
    externalJars(project(':workflow-native'))
    
    // Ballerina standard libraries
    ballerinaStdLibs "io.ballerina.stdlib:io-ballerina:${stdlibIoVersion}"
}

task updateTomlFiles {
    doLast {
        def ballerinaTomlFile = file("${projectDir}/Ballerina.toml")
        def newBallerinaToml = ballerinaTomlFile.text.replace("@project.version@", version)
        newBallerinaToml = newBallerinaToml.replace("@ballerina.lang.version@", ballerinaVersion)
        ballerinaTomlFile.text = newBallerinaToml
        
        def dependenciesTomlFile = file("${projectDir}/Dependencies.toml")
        def newDependenciesToml = dependenciesTomlFile.text.replace("@project.version@", version)
        dependenciesTomlFile.text = newDependenciesToml
    }
}

build.dependsOn('updateTomlFiles')
```

#### native/build.gradle
```groovy
plugins {
    id 'java-library'
    id 'checkstyle'
    id 'com.github.spotbugs'
}

description = 'Ballerina - Workflow Native Implementation'

dependencies {
    // Ballerina runtime
    implementation "io.ballerina.lang:ballerina-lang:${ballerinaLangVersion}"
    implementation "io.ballerina.lang:ballerina-runtime:${ballerinaLangVersion}"
    
    // Temporal SDK
    implementation "io.temporal:temporal-sdk:${temporalSdkVersion}"
    
    // gRPC (must match Temporal SDK version)
    implementation "io.grpc:grpc-netty-shaded:${grpcVersion}"
    implementation "io.grpc:grpc-protobuf:${grpcVersion}"
    implementation "io.grpc:grpc-stub:${grpcVersion}"
    
    // Logging
    implementation 'org.slf4j:slf4j-api:1.7.36'
    implementation 'ch.qos.logback:logback-classic:1.2.11'
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

checkstyle {
    toolVersion = '9.3'
    configFile = file("${rootProject.projectDir}/build-config/checkstyle/checkstyle.xml")
}

spotbugs {
    effort = 'max'
    reportLevel = 'low'
    excludeFilter = file("${projectDir}/spotbugs-exclude.xml")
}
```

#### compiler-plugin/build.gradle
```groovy
plugins {
    id 'java'
}

description = 'Ballerina - Workflow Compiler Plugin'

dependencies {
    // Ballerina compiler APIs
    implementation "io.ballerina.lang:ballerina-lang:${ballerinaLangVersion}"
    implementation "io.ballerina:ballerina-parser:${ballerinaLangVersion}"
    implementation "io.ballerina:ballerina-tools-api:${ballerinaLangVersion}"
    
    // Logging
    implementation 'org.slf4j:slf4j-api:1.7.36'
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
```

#### native-test/build.gradle (Shadow JAR)
```groovy
plugins {
    id 'java'
    id 'com.github.johnrengelman.shadow' version '8.1.1'
}

description = 'Ballerina - Workflow Test Server'

dependencies {
    // Temporal test framework
    implementation "io.temporal:temporal-testing:${temporalSdkVersion}"
    
    // gRPC server
    implementation "io.grpc:grpc-netty-shaded:${grpcVersion}"
    implementation "io.grpc:grpc-services:${grpcVersion}"
    
    // Logging
    implementation 'org.slf4j:slf4j-simple:1.7.36'
}

shadowJar {
    archiveBaseName.set('temporal-test-server')
    archiveClassifier.set('')
    archiveVersion.set('')
    
    manifest {
        attributes 'Main-Class': 'io.ballerina.stdlib.workflow.testutils.TestServer'
    }
    
    // Merge service provider files
    mergeServiceFiles()
}

build.dependsOn(shadowJar)
```

#### integration-tests/build.gradle
```groovy
// See 06-integration-testing.instructions.md for full details
// Manages embedded Temporal server lifecycle for tests
```

### 4. Build Commands

#### Full Build
```bash
# Clean and build all modules
./gradlew clean build

# Build without tests
./gradlew build -x test

# Build with verbose output
./gradlew build --info

# Build with stacktrace on error
./gradlew build --stacktrace
```

#### Module-Specific Builds
```bash
# Build native implementation only
./gradlew :workflow-native:build

# Build compiler plugin only
./gradlew :workflow-compiler-plugin:build

# Build Ballerina module only
./gradlew :workflow-ballerina:build

# Build test server shadow JAR
./gradlew :workflow-native-test:shadowJar
```

#### Test Commands
```bash
# Run all tests (unit + integration + compiler plugin)
./gradlew test

# Run unit tests only (no Temporal server)
./gradlew :workflow-ballerina:test

# Run integration tests (with embedded Temporal server)
./gradlew :workflow-integration-tests:test

# Run compiler plugin tests
./gradlew :workflow-compiler-plugin-tests:test

# Run tests with verbose output
./gradlew test --info

# Run specific test
./gradlew :workflow-ballerina:test --tests 'testRegistration'
```

#### Publishing
```bash
# Update Ballerina.toml with version placeholders
./gradlew :workflow-ballerina:updateTomlFiles

# Build BALA (Ballerina Archive)
./gradlew :workflow-ballerina:build

# Publish to local Maven repository
./gradlew publishToMavenLocal

# Publish to Ballerina Central (requires authentication)
cd ballerina
bal push
```

#### Code Quality
```bash
# Run Checkstyle
./gradlew checkstyleMain checkstyleTest

# Run SpotBugs
./gradlew spotbugsMain spotbugsTest

# Generate coverage report
./gradlew jacocoTestReport
```

### 5. Ballerina.toml Configuration

```toml
[package]
org = "ballerina"
name = "workflow"
version = "@project.version@"
distribution = "@ballerina.lang.version@"

[[platform.java21.dependency]]
path = "../native/build/libs/workflow-native-@project.version@.jar"
groupId = "io.ballerina.stdlib"
artifactId = "workflow-native"
version = "@project.version@"

[build-options]
observabilityIncluded = true
```

### 6. Dependencies.toml

```toml
[ballerina]
dependencies-toml-version = "2"

[[package]]
org = "ballerina"
name = "workflow"
version = "@project.version@"
modules = [
    {org = "ballerina", packageName = "workflow", moduleName = "workflow"}
]
```

### 7. Gradle Wrapper

The project includes Gradle wrapper for consistent builds:
```bash
# Unix/Linux/macOS
./gradlew build

# Windows
gradlew.bat build

# Upgrade wrapper version
./gradlew wrapper --gradle-version=8.5
```

## Dependency Version Constraints

### Critical Constraints
1. **Temporal SDK 1.32.0 requires gRPC 1.58.1**: Mismatch causes runtime errors
2. **Java 21 required**: Temporal SDK 1.32.0 uses Java 21 features
3. **Ballerina 2201.13.0**: Module built against this version

### Dependency Tree
```
workflow-ballerina
├── workflow-native.jar
│   ├── ballerina-runtime (2201.13.0)
│   ├── temporal-sdk (1.32.0)
│   │   └── grpc-netty-shaded (1.58.1)
│   └── logback (1.2.11)
└── io-ballerina (via Dependencies.toml)

workflow-compiler-plugin.jar
└── ballerina-tools-api (2201.13.0)

temporal-test-server.jar (shadow JAR)
├── temporal-testing (1.32.0)
└── grpc-services (1.58.1)
```

## Build Optimization

### Gradle Properties for Performance
```properties
org.gradle.jvmargs=-Xmx4g         # 4GB heap for build
org.gradle.caching=true           # Enable build cache
org.gradle.parallel=true          # Parallel module builds
org.gradle.daemon=true            # Persistent Gradle daemon
```

### Incremental Builds
```bash
# Build only changed modules
./gradlew build

# Force rebuild all
./gradlew clean build

# Build offline (use cached dependencies)
./gradlew build --offline
```

## Troubleshooting

### Common Issues

**Issue: Version mismatch errors**
```bash
# Clean all build artifacts
./gradlew clean
rm -rf ballerina/target
rm -rf ballerina/build

# Rebuild from scratch
./gradlew build
```

**Issue: Temporal SDK compatibility**
```bash
# Verify gRPC version matches Temporal SDK
./gradlew dependencies | grep grpc
./gradlew dependencies | grep temporal
```

**Issue: Ballerina module not found**
```bash
# Update TOML files with correct versions
./gradlew :workflow-ballerina:updateTomlFiles

# Check Ballerina build
cd ballerina
bal build
```

**Issue: Test server won't start**
```bash
# Check port 7233 is available
lsof -i :7233  # Unix/Mac
netstat -ano | findstr :7233  # Windows

# Check shadow JAR contents
jar tf native-test/build/libs/temporal-test-server.jar | grep TestServer
```

## Success Criteria

✅ **Build:**
- `./gradlew build` completes successfully
- All modules compile without errors
- Native JAR contains Temporal SDK and dependencies
- Compiler plugin JAR contains Ballerina compiler APIs
- Test server shadow JAR is executable

✅ **Dependencies:**
- Temporal SDK version matches gRPC version
- Ballerina runtime version matches compiler version
- No dependency conflicts in classpath

✅ **Tests:**
- Unit tests run without Temporal server
- Integration tests run with embedded server
- Compiler plugin tests validate error messages
- All tests pass without flakiness

✅ **Publishing:**
- BALA builds with correct version
- Dependencies.toml has correct version placeholders
- Module can be published to Ballerina Central
- Native JAR included in published package

✅ **Development Workflow:**
- Incremental builds work correctly
- Build cache improves rebuild times
- Code quality checks pass (checkstyle, spotbugs)
- Documentation builds successfully
