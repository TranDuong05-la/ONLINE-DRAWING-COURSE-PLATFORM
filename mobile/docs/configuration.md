# ArtNest app configuration

## Rule of thumb

Everything compiled into the Flutter app can be extracted from the APK or the web bundle.
So the app only ever holds **public** values. Secrets live on the Spring Boot server.

| Kind | Examples | Where it lives |
|---|---|---|
| A. Public app config | API base URL, Google **Web client ID** | `ApiConstants`, `--dart-define` / `config/dart_defines.json` |
| B. Per-user session data | access token, refresh token | `flutter_secure_storage` via `TokenStorage` (never logs, never SharedPreferences) |
| C. Server-only secrets | JWT secret, DB password, SMTP password, Google client **secret** | `backend/.env` (git-ignored), read by `application.yaml` |

`--dart-define` values are **not secret**. They are baked into the compiled app.

## Build-time values

| Key | Default when not set | Used by |
|---|---|---|
| `API_BASE_URL` | Android: `http://10.0.2.2:8080`, web/desktop/iOS sim: `http://localhost:8080` | `lib/core/constants/api_constants.dart` |
| `GOOGLE_CLIENT_ID` | the project's Web client ID | `lib/widgets/google_sign_in_sheet.dart` (`serverClientId` on Android, `clientId` on web) |

`GOOGLE_CLIENT_ID` must be the **Web** OAuth client ID and must equal `GOOGLE_CLIENT_ID`
in `backend/.env`, because the backend checks the ID token's audience against it.
On web, also keep the `google-signin-client_id` meta tag in `web/index.html` in sync.

Android Google Sign-In additionally needs an **Android** OAuth client in Google Cloud
Console for package `com.artnest.artnest_app` with the SHA-1 of the signing key
(debug key for `flutter run`; your release key for release builds).

### Using a config file

```sh
cp config/dart_defines.example.json config/dart_defines.json   # git-ignored
flutter run --dart-define-from-file=config/dart_defines.json
```

Or pass a single value: `flutter run --dart-define=API_BASE_URL=http://192.168.1.10:8080`.

Note: a server address saved from the in-app "server config" dialog is stored on the
device and takes priority over the build-time default until it is changed again.

## Backend address cheat sheet

| Where the app runs | Base URL |
|---|---|
| Android emulator | `http://10.0.2.2:8080` (`localhost` would point at the emulator itself) |
| Chrome / desktop | `http://localhost:8080` |
| Physical phone on same Wi-Fi | `http://<your-PC-LAN-IP>:8080` |

## Local disk usage

`build/` and `.dart_tool/` are generated and can grow past 1 GB (debug APKs,
Gradle intermediates, kernel snapshots). They are git-ignored. Reclaim the space with:

```sh
flutter clean
```
