package moe.rukamori.archivetune.shared

import platform.UIKit.*

/** UIKit owns sharing; track sources and credentials never leave the player. */
internal fun shareIosTrack(track: MusicTrack) {
    val root = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return
    var presenter: UIViewController = root
    while (presenter.presentedViewController != null) presenter = presenter.presentedViewController!!
    val sheet = UIActivityViewController(
        activityItems = listOf("${track.title} — ${track.artist}\nhttps://music.youtube.com/watch?v=${track.id}"),
        applicationActivities = null,
    )
    sheet.popoverPresentationController?.sourceView = presenter.view
    presenter.presentViewController(sheet, animated = true, completion = null)
}
