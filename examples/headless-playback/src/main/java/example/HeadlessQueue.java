package example;

import java.util.List;
import java.util.Map;
import kotlin.random.Random;
import moe.rukamori.archivetune.playback.api.PlaybackItem;
import moe.rukamori.archivetune.playback.api.PlaybackMetadata;
import moe.rukamori.archivetune.playback.api.PlaybackQueue;
import moe.rukamori.archivetune.playback.api.PlaybackSource;
import moe.rukamori.archivetune.sdk.audio.BeatTimelineKt;

public final class HeadlessQueue {
    public static void main(String[] args) {
        var metadata = new PlaybackMetadata(
                "track-id", "Example", List.of(), null, null, null, false, false,
                Map.of(), false, null, null, null, null);
        var item = new PlaybackItem(metadata, new PlaybackSource.Deferred(metadata.getId()));
        var queue = new PlaybackQueue(Random.Default);
        queue.replace(List.of(item, item), 0);
        if (queue.advance(false).getIndex() != 1 || queue.entries().size() != 2) {
            throw new IllegalStateException("Queue did not preserve duplicate tracks");
        }
        var beat = BeatTimelineKt.sampleBeatTimeline(new float[] {0f, 0.5f, 1f}, 750.0);
        if (beat.getBeatIndex() != 1) {
            throw new IllegalStateException("Beat timeline did not use playback position");
        }
        System.out.println("Headless queue advanced to duplicate track at index "
                + queue.getCurrentIndex());
    }
}
