# Desktop Last.fm credentials

Desktop builds read these properties from the repository root's `local.properties`, just like Android:

```properties
LASTFM_API_KEY=
LASTFM_SECRET=
```

Fill in both values locally, then rebuild Desktop. Nonblank local values take priority over environment variables with the same names; blank or missing entries fall back to the environment. Missing credentials do not prevent a build; the app still supports entering a credential override in Settings.

The generator writes only these two values into Kotlin source under `desktopApp/build/generated/`. It does not package the entire `local.properties` file. Both `local.properties` and generated build outputs are ignored by Git. Credentials are included in the built application, so keep the source configuration out of commits and shared logs.

The Last.fm service uses build credentials for the default Last.fm endpoint. A complete saved credential override and its account session take priority, using the existing Windows DPAPI store. Application defaults are not sent to alternate service endpoints.
