# CheckMate

An Android TV companion for Nuvio: one live-TV catalog, channels combined across your own providers, and automatic source screening on your TV. No separate server or cloud account is required.

CheckMate supplies **no channels, provider accounts, subscriptions, or playback credentials**. Add manifests from providers you are authorized to use.

## What it does

- Combines matching live channels into one entry, preserving language, region, and distinct names such as Super Hungama.
- Sorts channel results by the number of distinct configured providers, then alphabetically.
- Prioritizes advertised stream quality. It screens four candidates concurrently, with at most two Android video decoders active.
- Uses a one-minute checking/retry budget. HTTP failures are rejected; a native-player limitation stays inconclusive so Nuvio can try it.
- Keeps playback markers in private device storage and refreshes expiring sources after restart.
- Offers optional Nuvio profile selection and source-error recovery through an accessibility service.
- Supports an optional BLE iTag button: one click opens Hungama; two quick clicks open SmartTube Beta. Reports battery level and attempts to silence supported disconnect alarms.

Only the **CheckMate live-TV catalog** is checked. Existing Nuvio addons, movies, series, and other content keep their normal behavior. Other addons can still display their own rows in global search.

## Download

Download the APK from [GitHub Releases](https://github.com/HackyRoot/CheckMate/releases/latest). Release APKs are signed; the signing key is not part of this repository. CI artifacts are debug builds and use a different key.

## Setup on your TV

1. Sideload CheckMate on Android TV 8.0 or newer, then open it.
2. Open **Setup** and choose **Import installed providers from Nuvio**. In Nuvio first open **Settings → Addons → Manage from phone**, leave the QR screen active, then return to CheckMate and import. Review and save. This reads Nuvio’s localhost configuration on the same TV; nothing is uploaded to CheckMate’s developer. Alternatively enter manifest URLs manually, one per line.
3. In Nuvio, install this addon URL **on the same TV**:
   ```text
   http://127.0.0.1:18765/manifest.json
   ```
4. Enable CheckMate's addon, move it to the top if desired, and use Nuvio's **Play first stream** setting.
5. Search or browse the **Live TV** row from CheckMate. Original provider rows remain available.
6. Optional: enable **CheckMate** in Android's accessibility settings for automatic TV-profile selection and playback-error recovery. This service inspects only Nuvio's UI; it is not needed for catalog aggregation.
7. Optional: enter your tag's Bluetooth MAC address. Grant Bluetooth/location permission when requested. A tag is not required for live-TV checking.

If Nuvio's TV profile inherits addons, install CheckMate under the primary profile. Provider configuration is device-local; opening the localhost URL from another device does not reach the TV.

The configured tag must expose service `FFE0`, button characteristic `FFE1`, and compatible notification behavior. Battery uses standard `180F/2A19`. Some clones ignore alarm controls or report inaccurate battery percentages. The tag cannot send an IR TV power command.

## Getting a provider URL: CNCVerse Bridge example

CheckMate needs a **manifest URL**, not a provider’s configuration-page address.

1. Open the [CNCVerse Bridge configuration site](https://cncverse.dpdns.org/) on your phone or computer.
2. Select or configure a profile with the live-TV sources you want. Keep its catalogs enabled.
3. Choose **Copy Addon URL** and copy the complete address ending in `/manifest.json` (it may include a private profile path or query token).
4. Paste that address into CheckMate’s provider field and save. Use your own profile; do not publish its URL.
5. Install CheckMate’s separate localhost manifest in Nuvio as described above.

If you use the CNCVerse Bridge **application** instead of the hosted service, its Server screen displays its manifest address. A bridge running on the same TV may show `http://127.0.0.1:8080/manifest.json`; one running on another machine requires that machine’s reachable LAN address. Keep that bridge running. See the [provider’s own setup instructions](https://github.com/NivinCNC/CNCVerse-Bridge#readme).

A local address from your phone refers to the phone itself, not your TV. CheckMate never includes somebody else’s provider credentials or copies CNCVerse’s source code.

## Build

Requires JDK 17 and Android SDK platform 34. Set `ANDROID_HOME` or create an untracked `local.properties` containing your SDK location.

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions also builds a debug APK artifact. This initial version targets Android TV sideloading and is not a Play Store release.

## Limits

- “Listed by multiple providers” does not mean all those providers currently work.
- Quality ranking uses provider labels; it does not guarantee a measured resolution or bitrate.
- Codec, DRM, authentication, geo restrictions, provider outages, and a TV's hardware can affect playback. A reachable playlist alone is not reported as proven video.
- Automatic recovery watches explicit Nuvio playback errors for CheckMate-owned sources. It cannot reliably diagnose every silent decoder stall.
- Keep the companion running for its localhost addon to work. It starts a foreground service and requests restart after TV boot; firmware can still restrict background apps.
- Cold searches query several providers and can take seconds. Repeated searches reuse short-lived channel membership; stream URLs are fetched fresh.

For an early experimental build or a CI artifact signed with another key, Android may require reinstalling before using the release APK. Save your provider configuration first.

## Privacy

There is no CheckMate analytics or hosted backend. Requests go from your TV to your configured providers. Provider URLs, credentials, tag addresses, and playback sessions stay on your device and are not included in this repository or its APK. Android backup is disabled. Providers and Nuvio retain their own privacy policies.

## Development

The app uses Android platform APIs and Java, without third-party runtime dependencies. The Gradle wrapper is included for reproducible builds. The application identifier remains `local.tagbridge` for compatibility with early installations.

Licensed under MIT. Gradle and its wrapper remain subject to their own Apache-2.0 license.
