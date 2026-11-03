import 'package:flutter/foundation.dart';

/// Debug-only logging. `debugPrint` alone still writes to logcat in release
/// builds, so route diagnostics through here instead.
///
/// Never pass tokens, passwords, OTP codes or personal data (e.g. email).
void logDebug(String message) {
  if (kDebugMode) debugPrint(message);
}
