# ArchiveTune desktop library

This JVM-only module scans user-selected music folders and reads metadata without native/JNI
dependencies. It uses [jaudiotagger](https://www.jthink.net/jaudiotagger/), licensed under the
LGPL-2.1-or-later. The dependency is retrieved from Maven Central. Audio files stay on disk;
jaudiotagger reads only the metadata needed by the desktop index. Embedded artwork is deliberately
not copied into indexed tracks, so a library scan cannot retain one large cover byte array per song.
