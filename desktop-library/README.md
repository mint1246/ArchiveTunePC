# ArchiveTune desktop library

This JVM-only module scans user-selected music folders and reads metadata without native/JNI
dependencies. It uses [jaudiotagger](https://www.jthink.net/jaudiotagger/), licensed under the
LGPL-2.1-or-later. The dependency is retrieved from Maven Central. Audio files stay on disk;
jaudiotagger reads their container metadata and the module only retains embedded artwork up to
5 MiB per track.
