/* ArchiveTune (2026), GPL-3.0. See LICENSE. */
package moe.rukamori.archivetune.linuxmedia;

import java.util.Map;
import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.TypeRef;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.annotations.DBusProperties;
import org.freedesktop.dbus.annotations.DBusProperty;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.types.Variant;

/** Explicit wire signatures also allow desktop clients to introspect the service. */
public final class MprisInterfaces {
    private MprisInterfaces() {}
    public interface MetadataType extends TypeRef<Map<String, Variant<?>>> {}
    @DBusInterfaceName("org.mpris.MediaPlayer2")
    @DBusProperties({
        @DBusProperty(name="Identity", type=String.class, access=DBusProperty.Access.READ),
        @DBusProperty(name="DesktopEntry", type=String.class, access=DBusProperty.Access.READ),
        @DBusProperty(name="CanQuit", type=Boolean.class, access=DBusProperty.Access.READ),
        @DBusProperty(name="CanRaise", type=Boolean.class, access=DBusProperty.Access.READ),
        @DBusProperty(name="HasTrackList", type=Boolean.class, access=DBusProperty.Access.READ),
        @DBusProperty(name="SupportedUriSchemes", type=String[].class, access=DBusProperty.Access.READ),
        @DBusProperty(name="SupportedMimeTypes", type=String[].class, access=DBusProperty.Access.READ)
    })
    public interface Root extends DBusInterface { void Raise(); void Quit(); }
    @DBusInterfaceName("org.mpris.MediaPlayer2.Player")
    @DBusProperties({
        @DBusProperty(name="PlaybackStatus", type=String.class, access=DBusProperty.Access.READ),
        @DBusProperty(name="LoopStatus", type=String.class, access=DBusProperty.Access.READ_WRITE),
        @DBusProperty(name="Rate", type=Double.class, access=DBusProperty.Access.READ_WRITE),
        @DBusProperty(name="Shuffle", type=Boolean.class, access=DBusProperty.Access.READ_WRITE),
        @DBusProperty(name="Metadata", type=MetadataType.class, access=DBusProperty.Access.READ),
        @DBusProperty(name="Volume", type=Double.class, access=DBusProperty.Access.READ_WRITE),
        @DBusProperty(name="Position", type=Long.class, access=DBusProperty.Access.READ),
        @DBusProperty(name="MinimumRate", type=Double.class, access=DBusProperty.Access.READ),
        @DBusProperty(name="MaximumRate", type=Double.class, access=DBusProperty.Access.READ),
        @DBusProperty(name="CanGoNext", type=Boolean.class, access=DBusProperty.Access.READ),
        @DBusProperty(name="CanGoPrevious", type=Boolean.class, access=DBusProperty.Access.READ),
        @DBusProperty(name="CanPlay", type=Boolean.class, access=DBusProperty.Access.READ),
        @DBusProperty(name="CanPause", type=Boolean.class, access=DBusProperty.Access.READ),
        @DBusProperty(name="CanSeek", type=Boolean.class, access=DBusProperty.Access.READ),
        @DBusProperty(name="CanControl", type=Boolean.class, access=DBusProperty.Access.READ)
    })
    public interface Player extends DBusInterface {
        void Next(); void Previous(); void Pause(); void PlayPause(); void Stop(); void Play();
        void Seek(long offset); void SetPosition(DBusPath trackId, long position); void OpenUri(String uri);
        class Seeked extends DBusSignal {
            public Seeked(String path, long position) throws DBusException { super(path, position); }
        }
    }
}
