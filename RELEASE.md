# FileZen — Release guide (Google Play)

## 1. Create an upload key (once)

Play uses **Play App Signing**: Google holds the app-signing key, you hold an
upload key.

```bash
keytool -genkeypair -v -keystore release.keystore -alias filezen-upload \
    -keyalg RSA -keysize 2048 -validity 10000
```

Keep `release.keystore` and its passwords **out of git**.

## 2. Point Gradle at the key

Create `keystore.properties` in the repo root (git-ignored):

```properties
storeFile=release.keystore
storePassword=<keystore password>
keyAlias=filezen-upload
keyPassword=<key password>
```

The `releaseUpload` signing config in `app/build.gradle.kts` reads it.
Add to `buildTypes.release`:

```kotlin
signingConfig = signingConfigs.getByName("releaseUpload")
```

(one line — intentionally not enabled by default so debug builds stay trivial.)

## 3. Build the AAB

```bash
./gradlew :app:bundleRelease
# → app/build/outputs/bundle/release/app-release.aab
```

Also verify the release build compiles & R8 passes:

```bash
./gradlew :app:assembleRelease
```

## 4. Production items to fill in

| What | Where | Action |
|---|---|---|
| AdMob App ID | `app/build.gradle.kts` → `manifestPlaceholders.admobAppId` | Replace test ID `ca-app-pub-3940256099942544~3347511713` with yours (AdMob → Apps → App settings) |
| Banner ad unit | `ads/Ads.kt` → `Ads.BANNER_UNIT_ID` | Replace test unit `ca-app-pub-3940256099942544/6300978111` |
| Remove-ads product | `billing/BillingManager.kt` → `PRODUCT_ID` = `"remove_ads"` | Create matching in-app product in Play Console → Monetise → Products → In-app products (one-time, non-consumable) |
| `versionCode`/`versionName` | `app/build.gradle.kts` → `defaultConfig` | Bump every release |

## 5. Permissions declaration in Play Console

FileZen requests `MANAGE_EXTERNAL_STORAGE` (**All files access**). File managers
are a permitted category. In the declaration form, state e.g.:

> FileZen is a file manager. All files access is required to browse, move, copy,
> rename, compress and organise files anywhere on shared storage — the app's core
> purpose. No other apps' private data is accessed.

Also declare `READ_MEDIA_*` fallback usage.

## 6. Store listing quick reference

- **Category:** Tools → File management
- **Short description:** *A tidy file manager — new files land in your Inbox, file them in one tap.*
- **Content rating:** everyone.
- **Data safety:** no data collected or shared; all processing on-device.
- **Ads:** yes (banner, Home + Storage only) until Play Console ad declaration;
  mark "Contains ads". The `remove_ads` IAP removes them.

## 7. Versioning / release

1. Bump `versionCode` + `versionName`.
2. `./gradlew :app:bundleRelease` → upload `app-release.aab`.
3. Enrol in Play App Signing when prompted (first upload) — Google generates the
   app-signing certificate; keep the upload key safe.

### What's new in 1.2.6 (versionCode 9)

- **Move to folder… everywhere** — the ⋮ menu is now also on grid cards, and the
  selection bar in Browse, Recent files and the file calendar all gain
  "Move to folder…" plus a bulk "Send to Transfer folder".
- **Transfer web app → full file manager** — the PC page now browses the whole
  phone: root chips (Internal, Downloads, Documents, Pictures, DCIM, Movies,
  Music, FileZen Share), breadcrumb navigation, image/video thumbnails,
  checkboxes + Select-all, bulk Download-as-ZIP and bulk Delete, upload into
  the folder being browsed, and a New-folder button.
- **Transfer runs in background** — the HTTP server now lives in a
  `dataSync` foreground service with a persistent notification (URL + Stop
  action), so it keeps serving while you switch screens or lock the phone.
  New permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`,
  `POST_NOTIFICATIONS` (declare in the Play Console form).
- **Calendar redesign** — month card with file-count heat intensity per day
  (busier days glow more), count badges, weekend column accent, "Jump to
  today", per-day size total, and Move/Send-to-Transfer actions for the day.
- **Sort rules screen** — rule cards with match-type icon, pattern and scope
  chips, dimmed disabled state, rule counter, and a "Run now" chip that sweeps
  watched folders + rule sources with all enabled rules immediately.
- **Inbox reliability fix** — new files now land reliably: the InboxViewModel
  restarts its FileObservers whenever watched roots change, and the screen
  rescans every time it resumes (the VM survives navigation).

### What's new in 1.2.5 (versionCode 8)

- **Move to folder…** — every file/folder (single or multi-select) can be moved to
  any folder on the device via a real folder browser (descend, go up, create a
  folder). Accessible from each file's ⋮ menu, the selection bar, the basket
  "Move" button, and the Inbox tidy sheet.
- **FileZen Share target** — "Send to Transfer folder" per file and a FileZen
  Share row in every move/tidy picker stage files for the Transfer-to-PC browser
  upload/download server.
- **Scoped sort rules + templates** — sort rules gained an optional "Only inside
  folder" scope (`sourcePath`, Room v3 migration) so e.g. `*.pdf` inside
  WhatsApp Documents → `Documents/WhatsApp` without touching other PDFs.
  One-tap templates cover PDFs, photos, video, music, docs, APKs, archives and
  WhatsApp media types; extension patterns accept comma lists.
- **Watched-folder quick add** — the Inbox watched-folders dialog offers
  one-tap presets (WhatsApp media, Camera, Screenshots, Telegram, FileZen
  Share) plus a real folder picker instead of typing paths.

## Testing notes (this build)

- Unit tests: `./gradlew :app:testDebugUnitTest` — cover the file engine
  (copy/move/delete/rename/mkdir), conflict policies, verify-before-delete,
  rename engine preview/collisions, sort-rule matching.
- On-device smoke test: install `app-debug.apk`, grant All files access, drop
  files into `Download/` and run the Inbox → Tidy up flow.

## 8. Optional before listing (recommended)

- **Crash reporting (optional):** FileZen has no crash SDK by design (offline,
  no data collection). If you want crash reports, add Firebase Crashlytics:
  apply `com.google.gms.google-services` + `com.google.firebase.crashlytics`
  plugins, add `firebase-crashlytics` dep, and drop `google-services.json`
  into `app/`. Update the Play "Data safety" form if you enable it.
- **Localization:** all strings live in `app/src/main/res/values/strings.xml`-style
  literals for now — before a global listing, extract to `strings.xml` and add
  `values-in/` (Indonesian) translations.
- **Baseline profile (cold start):** `androidx.profileinstaller` is already a
  dependency, so library profiles ship. For a full app profile, add the
  `baselineprofile` Gradle plugin + a macrobenchmark generator module
  (`:baselineprofile` running a simple startup+scroll journey) and point
  `baselineProfile.automaticGenerationDuringBuild` at it.
- **Parallel copy:** batches of ≥4 small files (<4 MB) now copy 4-way parallel;
  large files and directories stay sequential and verify-first.
