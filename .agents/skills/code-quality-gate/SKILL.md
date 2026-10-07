---
name: code-quality-gate
description: >-
  Use this skill to run automated quality verification, test suites, and build validation across Kotlin Android and Flutter codebases before finalizing changes.
---

# Code Quality Gate Workflow

This skill guides the step-by-step verification process to ensure zero regressions, clean builds, and passing test suites.

## Step 1: Detect Project Scope

Determine which subproject was modified:
* **Native Android (Sift)**: `sift_android/`
* **Flutter**: `flutter/`

---

## Step 2: Native Android Verification (`sift_android/`)

1. **Run Unit Tests**:
   ```powershell
   cd sift_android
   .\gradlew.bat testDebugUnitTest
   ```
   *Verify*: Output contains `BUILD SUCCESSFUL` with 0 test failures.

2. **Verify Full APK Compilation**:
   ```powershell
   .\gradlew.bat assembleDebug
   ```
   *Verify*: Generates `app-debug.apk` without linking or compilation errors.

3. **Check Android Lint / Deprecations**:
   Inspect compiler warnings in terminal output and address any deprecated API usages where feasible.

---

## Step 3: Flutter Verification (`flutter/`)

1. **Run Flutter Analyzer**:
   ```powershell
   cd flutter
   flutter analyze
   ```
   *Verify*: Must exit with `No issues found!`.

2. **Run Flutter Unit Tests**:
   ```powershell
   flutter test
   ```
   *Verify*: All test suites pass.

---

## Step 4: Git Status & Diff Cleanliness

Check for unintended file modifications or leftover scratch artifacts:
```powershell
git status --short
```
* Remove untracked temporary files or logs.
* Verify `.gitignore` rules prevent build artifacts from leaking into git.
