# Investigation Plan: Compare Old vs New Extension Builds

## Goal
Identify the exact change that caused newer extensions to stop working with manga-utils.

## First Step
Ask the user to provide:
- Older working APKs
- New failing APKs
- Release links
- Commit hashes
- Extension names

Prefer multiple versions of the SAME extension.

## Build Timeline
Record:
- Version
- VersionCode
- Release date
- Commit
- APK size
- Kotlin metadata
- Compiler version
- AGP version
- DEX version

## Compare

### Source
Diff build files, Gradle, Kotlin, dependencies, plugins, compiler settings.

### APK
Compare:
- AndroidManifest
- classes.dex
- META-INF
- Resources
- Metadata

### Bytecode
After translation compare:
- Superclasses
- Interfaces
- Descriptors
- StackMapFrames
- Lambdas
- Companion objects
- Synthetic classes
- Access flags
- Bootstrap methods
- Constant pool

### Kotlin
Compare metadata version, stdlib, reflection, coroutine output, IR output.

### Translator
Determine whether the translator assumes an older compiler output or bytecode layout.

### Regression
Find:
- Last working version
- First failing version
Explain exactly what changed.

### Cross-check
Compare findings with Suwayomi's current loader and compatibility layer.

## Deliverables
- Version timeline
- Source diff report
- APK diff report
- Bytecode report
- Metadata report
- Regression point
- Required manga-utils changes
- Long-term compatibility strategy
