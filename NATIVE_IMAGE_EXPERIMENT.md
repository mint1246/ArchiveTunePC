# ArchiveTune GraalVM Native Image experiment

Status: experimental only. Existing OpenJ9/JVM packaging must remain functional and unchanged.

Goal: determine whether ArchiveTune can launch as a GraalVM Native Image using Nucleus Framework, with special attention to VLCJ/JNA and Rhino compatibility.

Rules:
- Do not replace the existing Compose Desktop/OpenJ9 build path.
- Do not commit generated GraalVM/native-image artifacts.
- Prefer Nucleus' curated reachability metadata before hand-writing metadata.
- Stop and document if a fundamental blocker is found.

Initial notes:
- Nucleus 2.x provides curated Native Image metadata for Compose UI, Skiko, Ktor, Coil, kotlinx.serialization, JNA and the JDK/AWT baseline.
- ArchiveTune's likely application-specific problem areas are VLCJ/JNA native library loading and Rhino's dynamic code paths.
- Nucleus is being evaluated as an additive experiment; the normal Compose/OpenJ9 tasks remain the source of truth until this proves viable.
