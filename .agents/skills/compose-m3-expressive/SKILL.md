---
name: compose-m3-expressive
description: >-
  Use this skill when developing or reviewing Jetpack Compose components with Material 3 Expressive aesthetics, spring animations, dynamic theming, and smooth list performance.
---

# Material 3 Expressive in Jetpack Compose

This skill provides patterns and best practices for crafting fluid, high-aesthetic interfaces using Material 3 Expressive tokens.

## 1. Expressive Shape Scales
Use asymmetric and playful rounded shapes:
```kotlin
val SiftShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp), // Cards, bottom sheets
    large = RoundedCornerShape(28.dp),  // Split buttons, FABs
    extraLarge = RoundedCornerShape(36.dp) // Search bars, filter chips
)
```

## 2. Dynamic Tonal Color System
* Never hardcode color values in widgets.
* Support Monet dynamic theming on Android 12+ (`dynamicDarkColorScheme(context)`).
* Use container color roles:
  * Primary Container / On-Primary Container for key emphasis (Hero card, selected chips).
  * Surface Container / Surface Variant for cards and scrim overlays.
  * Tonal elevation (`tonalElevation = 4.dp`) for bottom bars and app bars.

## 3. Motion & Transitions
* Use `Crossfade` or `AnimatedContent` for screen transitions.
* Use `AnimatedVisibility(visible = ..., enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically())`.
* Use spring physics for interactive feedback:
  ```kotlin
  val scale by animateFloatAsState(
      targetValue = if (isPressed) 0.96f else 1f,
      animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
  )
  ```

## 4. Coil Image Performance in Compose
* Always specify `crossfade(true)`.
* Set an explicit aspect ratio on the image container to avoid layout shifts during lazy loading.
* Use `clip(MaterialTheme.shapes.medium)` on the card container rather than ad-hoc clipping.
