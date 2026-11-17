# Desktop resource coordination

This JVM module contains the existing desktop bounded memory cache, persistent content cache and single-flight request coordinator. Callers own account-scoped keys, invalidation, resource lifetime and the decision about what may be persisted. Do not put cookies, authorization headers or signed stream URLs in cache keys or payloads.

Desktop repositories continue using the same implementation and cache files. This module does not itself provide account isolation or a complete backend session.
