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

## Testing notes (this build)

- Unit tests: `./gradlew :app:testDebugUnitTest` — cover the file engine
  (copy/move/delete/rename/mkdir), conflict policies, verify-before-delete,
  rename engine preview/collisions, sort-rule matching.
- On-device smoke test: install `app-debug.apk`, grant All files access, drop
  files into `Download/` and run the Inbox → Tidy up flow.
