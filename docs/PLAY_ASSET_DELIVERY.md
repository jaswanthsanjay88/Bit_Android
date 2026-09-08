# Google Play Asset Delivery (PAD) Migration Blueprint

## Executive Summary
This document defines the architectural roadmap for transitioning BIT's Linux developer environment from **runtime remote downloads** to **Google Play Asset Delivery (PAD)**.

While runtime mirrors (Canonical, Alpine CDN) combined with cryptographic SHA-256 validation and upfront consent satisfy transparency and integrity standards, Google Play's automated scanners and review policies strictly prohibit downloading executable machine code (ELF binaries, `.so` shared objects) from non-Play sources. Bundling a verified base rootfs into an `.aab` asset pack permanently solves this issue by routing all operating system assets through Google Play's official delivery pipeline.

---

## 1. Compliance Analysis & Precedents

| Approach | Google Play Risk | Data Safety & Compliance | User Experience |
| :--- | :---: | :--- | :--- |
| **Runtime Remote Downloads (Current)** | **HIGH** | Requires explicit consent dialogs, policy disclosures, and external mirror fetching. Vulnerable to static scan flags. | Requires network; user waits for 4MB–35MB download. |
| **Play Asset Delivery (PAD)** | **ZERO** | 100% Google Play compliant. Assets are signed, hosted, and delivered by Google Play CDN. | Pre-installed or on-demand via Google Play. Instant offline availability. |

---

## 2. Play Asset Delivery Architecture

Play Asset Delivery allows Android App Bundles (`.aab`) to package up to 2 GB of non-executable game and application assets across three delivery modes:
1. **`install-time`**: Downloaded concurrently with the app installation. Available immediately on first launch.
2. **`fast-follow`**: Downloaded automatically by Google Play immediately after the app installs.
3. **`on-demand`**: Downloaded in the background via the Play Core library when the user navigates to the feature.

### Recommended Topology for BIT
* **Base Rootfs Asset Pack (`:rootfs_alpine`)**: `install-time` delivery.
  * Size: **~3.5 MB compressed** (Alpine Linux minirootfs aarch64).
  * Impact on APK/AAB size: Negligible (< 4 MB).
  * Benefit: Ready immediately out-of-the-box. The user can create a workspace and launch Python or Bash offline with zero network latency.
* **Full Developer Suite Asset Pack (`:rootfs_ubuntu`)**: `on-demand` delivery.
  * Size: **~35 MB compressed** (Ubuntu Base Noble 24.04).
  * Delivered via `com.google.android.play:asset-delivery`.

---

## 3. Implementation Steps

### Phase 1: Create the Asset Pack Module
In `settings.gradle.kts`:
```kotlin
include(":app")
include(":workspace")
include(":rootfs_alpine")
```

In `rootfs_alpine/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("rootfs_alpine")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}
```

Place the official verified archive:
```
rootfs_alpine/src/main/assets/
└── rootfs_alpine_aarch64.tar.gz (SHA-256 verified)
```

### Phase 2: App-Side Integration
In `app/build.gradle.kts`:
```kotlin
android {
    assetPacks = [":rootfs_alpine"]
}

dependencies {
    implementation("com.google.android.play:asset-delivery:2.2.2")
    implementation("com.google.android.play:asset-delivery-ktx:2.2.2")
}
```

### Phase 3: Runtime Unpacking
In `RootfsInstaller.kt`, add an installation path for asset packs:
```kotlin
fun installFromAssetPack(
    root: String,
    assetPackName: String,
    assetFileName: String,
    onProgress: (RootfsInstallProgress) -> Unit
) {
    val assetManager = context.createPackageContext(context.packageName, 0).assets
    assetManager.open(assetFileName).use { inputStream ->
        // Extract directly into stagingDir -> linuxDir
        extractTarStream(inputStream, stagingDir, ArchiveFormat.TAR_GZ, onProgress)
    }
    patcher.patch(linuxDir)
}
```

---

## 4. Key Milestones & Sequencing
1. **Milestone 1 (Immediate / Completed):**
   - Reconcile Google Play metadata and public Privacy Policy disclosures.
   - Introduce explicit interstitial consent dialog before any runtime fetch.
   - Enforce cryptographic SHA-256 verification on all downloads.
   - Isolate Python pip installs into `/opt/bit-env` (respecting PEP 668).
2. **Milestone 2 (PAD Transition):**
   - Configure `:rootfs_alpine` asset pack module.
   - Replace the remote Alpine download with `install-time` asset pack extraction.
   - Retain custom URL downloads for advanced developers with prominent warning dialogs.
