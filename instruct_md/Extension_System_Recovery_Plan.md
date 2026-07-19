# Extension System Recovery & Root Cause Investigation Plan

## Primary Goal
Restore full Suwayomi-compatible extension functionality. A fix is **not complete** until extensions can install, load, update, search, read chapters, uninstall/reinstall, survive restarts, and operate without VerifyErrors, StackMapFrame issues, ClassLoader conflicts, or cache corruption.

## Current Status
- Repository index works
- Metadata downloads
- APK downloads
- APK → JAR translation completes
- Metadata cache works

Still broken:
- Install
- Load
- Update verification
- Source discovery
- Reader/search
- Runtime loading

Observed failure:

```text
InvocationTargetException
Caused by:
java.lang.VerifyError: Bad type on operand stack
Type kotlin.jvm.internal.Lambda is not assignable to y
```

Treat the generated JAR as suspect until proven otherwise.

## Investigation Priorities

### 1. Study Suwayomi
Clone and inspect the entire Suwayomi Server codebase.
Map the complete extension pipeline:
Repository → APK download → manifest → relocation → bytecode rewrite → JAR generation → cache → verification → class loading → registration → source discovery → reader.

Compare every stage with manga-utils.

### 2. Translator Audit
Verify:
- Kotlin lambdas
- Singleton objects
- Companion objects
- invoke-dynamic
- StackMapFrames
- Generic signatures
- Access flags
- Synthetic classes
- Class hierarchy
- Constant pool
- Bootstrap methods

Disassemble generated JARs with javap, ASMifier, CFR, FernFlower, JADX, and Bytecode Viewer.

### 3. Runtime Audit
Compare Kotlin runtime, Java version, ASM, Dex2Jar, R8/D8, coroutines, serialization, Netty, JNA, OkHttp, metadata libraries, JVM target.

Audit ClassLoader isolation, cache, duplicate classes, unloading, restart behavior.

### 4. Cache & Install Pipeline
Validate install, reinstall, update, rollback, restart persistence, cache invalidation, interrupted installs, stale cache detection.

### 5. Regression Investigation
The code reportedly worked 1–2 weeks ago.
Review every commit touching:
- Extension loader
- Translator
- Relocation
- Cache
- ASM
- Dependencies
- Gradle
- Kotlin
- Java target

Use git bisect if necessary.

## HIGH PRIORITY: Keiyoushi Build-System Investigation

Investigate PR #17529 (Cleanup gradle/build-logic) and related commits.

Determine whether newly built extensions changed because of:
- Kotlin compiler
- AGP
- Gradle
- R8/D8
- JVM target
- Kotlin metadata
- Lambda generation
- StackMapFrames
- invoke-dynamic
- Synthetic class generation

Build identical extensions before and after the PR.
Compare APKs, DEX, translated JARs, metadata, and bytecode.

Determine whether manga-utils assumes the old compiler output.

Compare against any Suwayomi compatibility changes made around the same timeframe.

## Deliverables
- Architecture document
- Pipeline diagram
- Suwayomi comparison
- Root cause analysis
- Regression analysis
- Binary/bytecode findings
- Required code changes
- Automated validation strategy

