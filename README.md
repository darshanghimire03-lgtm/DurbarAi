# Durbar Ai

Android library that adds the Durbar AI chat screen to any app.

## Add to your project

settings.gradle
```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

app/build.gradle
```groovy
dependencies {
    implementation 'com.github.YOUR_GITHUB_USERNAME:DurbarAi:1.0.0'
}
```

## Use

```kotlin
DurbarAi.open(this)
```

## Modules

- `durbar-ai` the library (package `com.durbar.ai`)
- `app` demo app

Gradle 8.7, AGP 8.5.2, Kotlin 1.9.24, JDK 17, compileSdk 34, minSdk 23.
