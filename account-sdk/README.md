# Saved account serialization

This experimental JVM module preserves Android's saved-account JSON format, coordinates YouTube login and account switching through injected backend and storage interfaces, and provides desktop cookie-export parsing with an injected login-cookie validator. It does not supply a browser, network backend, or credential storage. `SavedAccount` contains a login cookie; callers must protect serialized data and avoid logging account objects or JSON.

The Android app retains its existing DataStore integration. Tests cover compatibility with existing JSON, unknown fields, account order, duplicate entries, and the existing empty-list fallback for malformed data. The artifact is available locally as `moe.rukamori.archivetune:account-sdk:0.1.0-SNAPSHOT`; `examples/headless-accounts` demonstrates independent resolution without Android UI.
