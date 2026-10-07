# Code Quality & Architectural Standards

This rule governs coding standards, architecture hygiene, and verification procedures across this repository.

## 1. Kotlin & Jetpack Compose Standards (Sift App)

### State Management & Recomposition
* **Unidirectional Data Flow (UDF)**: ViewModels expose immutable `StateFlow<UiState>` and receive explicit user events/intents.
* **Stable Parameters**: Use immutable collections (`List`, `Set`) or `@Immutable` data classes to avoid unnecessary recomposition loops.
* **State Hoisting**: Always hoist state to the highest relevant composable; keep low-level leaf components stateless.
* **Side Effects**: Use `LaunchedEffect`, `rememberCoroutineScope`, or `DisposableEffect` with explicit keys. Never trigger side effects directly in the composable body.

### Material 3 Expressive Guidelines
* **Design Tokens**: Do not use hardcoded hex colors or arbitrary paddings. Always consume `MaterialTheme.colorScheme`, `MaterialTheme.shapes`, and `MaterialTheme.typography`.
* **Dynamic Theming**: Support edge-to-edge layout with `WindowCompat` and dynamic light/dark palettes extracted via `dynamicDarkColorScheme` / `dynamicLightColorScheme`.
* **Expressive Elevation & Shapes**: Use tonal elevation (`tonalElevation = 4.dp`) and expressive rounded corner scales (`12.dp`, `20.dp`, `28.dp`, `36.dp`).

### Concurrency & Coroutines
* **Dispatcher Discipline**: Always dispatch disk I/O, ML Kit inference, and HTTP requests to `Dispatchers.IO`. Keep ViewModel logic on `Dispatchers.Main`.
* **Structured Concurrency**: Bind all coroutines to `viewModelScope` or lifecycle-aware scopes.
* **Cancellation**: Never swallow `CancellationException` in try/catch blocks.

### Room Database & Local Cache
* **Indexes**: Always index foreign keys and columns frequently queried in `WHERE` clauses (e.g. `isJunk`, `dateAdded`).
* **Queries**: Use `Flow` return types for reactive UI updates and suspend functions for write/delete mutations.

---

## 2. Flutter & Dart Standards

* **Lint Cleanliness**: Adhere strictly to `flutter_lints`. Code must pass `flutter analyze` without errors.
* **Async Lifecycle Guards**: Always check `if (!mounted) return;` after `await` calls in `StatefulWidget` methods before calling `setState` or accessing `context`.
* **Resource Disposal**: Always dispose `AnimationController`, `TextEditingController`, `StreamSubscription`, and `ScrollController` in `dispose()`.
* **Model Immutability**: Use factory constructors (`fromJson`) with null-safe type checking.

---

## 3. Mandatory Verification Checklist

Before considering any task complete:
1. **Compilation**: Code must compile without errors.
2. **Tests**: Existing and new unit tests must pass (`./gradlew testDebugUnitTest` or `flutter test`).
3. **No Regressions**: Verify that existing APIs, comments, and unrelated features remain intact.
