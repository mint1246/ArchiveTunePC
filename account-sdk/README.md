# Saved account serialization

This experimental JVM module preserves Android's saved-account JSON format. It does not perform login, manage sessions, or provide credential storage. `SavedAccount` contains a login cookie; callers must protect serialized data and avoid logging account objects or JSON.

The Android app retains its existing DataStore integration. Tests cover compatibility with existing JSON, unknown fields, account order, duplicate entries, and the existing empty-list fallback for malformed data. The artifact is available locally as `moe.rukamori.archivetune:account-sdk:0.1.0-SNAPSHOT`; `examples/headless-accounts` demonstrates independent resolution without Android UI.
