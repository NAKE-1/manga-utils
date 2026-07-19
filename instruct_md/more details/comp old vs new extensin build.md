# Investigation Plan: Compare Old vs New Extension Builds

## Purpose

Determine whether the recent extension failures are caused by changes in the **extensions themselves** (compiler output, Gradle, Kotlin, R8/D8, metadata, bytecode, etc.) rather than a bug in manga-utils.

This investigation is separate from the Suwayomi codebase comparison and should focus on **binary and source differences between extension versions**.

---

# Goal

Find the exact point where extensions became incompatible with manga-utils.

Rather than assuming the application is wrong, determine **what changed in the generated extension APKs** and whether our translator or loader no longer supports those changes.

---

# IMPORTANT

Do **not** assume every extension is affected equally.

Different extensions may expose different compiler features or bytecode patterns.

Use multiple extensions from different dates.

---

# Ask Me for Test Extensions

Before beginning the investigation, ask me to provide one or more of the following:

- Older APK versions that previously worked
- Newer APK versions that now fail
- Git commits or tags
- GitHub release links
- Extension names
- APK files

Prefer comparing the **same extension** across multiple versions.

Example:

```
Extension:
MangaReadOrg

Version A:
Known working

↓

Version B:
First failing version

↓

Version C:
Latest version
```

Using the same extension removes unrelated variables.

---

# Build a Timeline

For every extension collected:

Record:

- Extension version
- Version code
- Release date
- Commit hash
- Repository commit
- Kotlin version (if detectable)
- AGP version (if detectable)
- Build timestamp
- APK size
- DEX version
- Metadata version

Build a chronological timeline.

---

# Source Code Comparison

If source is available:

Diff every version.

Determine:

- What actually changed?
- Was it only website parsing?
- Or were Gradle/build files modified?
- Were dependencies updated?
- Were compiler settings changed?
- Were plugins changed?
- Was Kotlin updated?
- Was AGP updated?
- Was JVM target updated?

Most website parsing changes should NOT break the JVM.

Compiler/toolchain changes might.

---

# APK Comparison

Compare every APK.

Inspect:

AndroidManifest.xml

classes.dex

resources

assets

META-INF

signatures

kotlin metadata

version metadata

compiler metadata

---

# DEX Comparison

Compare:

- Class count
- Method count
- Field count
- Package layout
- Synthetic classes
- Lambda classes
- Anonymous classes
- Companion objects
- Object declarations

Look for structural differences.

---

# Bytecode Comparison

After translating both APKs into JARs:

Compare:

Superclasses

Interfaces

Method descriptors

Generic signatures

Access flags

Bridge methods

Synthetic methods

Annotations

StackMapFrames

Bootstrap methods

InvokeDynamic

Constant pool

Inner classes

Nest members

Verify whether our translator generates different output.

---

# Kotlin Investigation

Compare:

Kotlin metadata version

stdlib version

reflect usage

serialization

compiler version

IR output

Lambda implementation

Object implementation

Companion implementation

Synthetic accessors

Suspend functions

Coroutines

---

# StackMapFrame Investigation

Specifically determine whether newer builds contain:

Different frame generation

Different verification expectations

Different bytecode layout

Additional frame information

Frame recomputation differences

---

# Lambda Investigation

Our VerifyError currently references:

```
kotlin.jvm.internal.Lambda
```

Determine whether newer extensions:

Generate different lambda classes

Generate different singleton objects

Generate different synthetic objects

Generate different invoke patterns

Generate different object initialization

---

# Translator Compatibility

Determine whether the translator assumes:

Old Kotlin compiler output

Old DEX layout

Old metadata

Old synthetic naming

Old StackMapFrames

Old lambda generation

If so, document every assumption.

---

# Runtime Compatibility

Determine whether newer extensions require:

New Kotlin runtime

New stdlib

New reflection library

New metadata parser

New JVM target

Different class loader behavior

---

# Regression Analysis

Identify the earliest version that:

Works

and

The first version that fails.

This is the regression point.

Determine exactly what changed between those two releases.

---

# Automated Comparison

If possible, build tooling that automatically compares:

Old APK

↓

New APK

Generate reports showing:

Added classes

Removed classes

Changed methods

Changed descriptors

Changed metadata

Changed compiler information

Changed bytecode

Changed StackMapFrames

Changed Kotlin metadata

Changed synthetic classes

---

# Cross-Reference with Suwayomi

After identifying differences, determine:

- Does Suwayomi explicitly support these newer bytecode patterns?
- Did Suwayomi update its loader or compatibility layer?
- Does manga-utils need equivalent support?

---

# Deliverables

Produce:

1. Timeline of extension versions.
2. Timeline of build/toolchain changes.
3. Binary comparison report.
4. Source comparison report.
5. Bytecode comparison report.
6. Kotlin metadata comparison.
7. StackMapFrame comparison.
8. Lambda generation comparison.
9. Regression point identification.
10. Exact explanation of why older versions work and newer versions fail.
11. List of code changes required in manga-utils.
12. Recommended compatibility strategy for supporting both old and new extension builds.

---

# Success Criteria

This investigation is complete only when:

- A known working extension has been compared to a failing version.
- The exact compatibility-breaking change has been identified (or confidently ruled out).
- The findings explain why manga-utils fails while Suwayomi continues to load the same extension.
- A concrete implementation plan is produced to restore compatibility with both older and newer extension builds.