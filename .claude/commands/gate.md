---
description: Run code quality gate verification across Android and Flutter
---

Run git status first:
```powershell
git status --short
```

Detect modified projects and execute tests:
- If files in `sift_android` were changed:
  ```powershell
  cd sift_android
  .\gradlew.bat testDebugUnitTest
  cd ..
  ```
- If files in `flutter` were changed:
  ```powershell
  cd flutter
  flutter analyze
  flutter test
  cd ..
  ```

Report the verification results clearly.
