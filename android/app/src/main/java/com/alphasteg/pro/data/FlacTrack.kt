package com.alphasteg.pro.data

/** One FLAC track on the device, identified by its absolute path. */
data class FlacTrack(
    val path: String,
    val name: String,
    val size: Long
) {
    /** Immediate containing folder, e.g. "Lost Society" for a track under /Music/Lost Society/. */
    val folder: String
        get() = path.substringBeforeLast('/', "").substringAfterLast('/', "")
}
