package com.funcron.hdmi_in_android

import com.serenegiant.usb.Format
import com.serenegiant.usb.UVCCamera
import com.serenegiant.usb.Size as UvcSize

/** A picture size in one pixel format, without a frame rate. */
data class Resolution(val frameType: Int, val width: Int, val height: Int) {
    val isMjpeg: Boolean
        get() = frameType == UVCCamera.UVC_VS_FRAME_MJPEG

    val label: String
        get() = "$width x $height  (${if (isMjpeg) "MJPEG" else "YUV"})"
}

/** One selectable capture mode: frame type + resolution + frame rate. */
data class VideoMode(
    val frameType: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val allFps: List<Int>
) {
    val isMjpeg: Boolean
        get() = frameType == UVCCamera.UVC_VS_FRAME_MJPEG

    val formatName: String
        get() = if (isMjpeg) "MJPEG" else "YUV"

    val resolution: Resolution
        get() = Resolution(frameType, width, height)

    val label: String
        get() = "${width}x$height @ $fps fps  ($formatName)"

    fun toSize(): UvcSize = UvcSize(frameType, width, height, fps, allFps)

    fun matches(size: UvcSize): Boolean =
        size.type == frameType && size.width == width && size.height == height && size.fps == fps

    companion object {

        /** The only frame rates offered in the mode picker. */
        val SELECTABLE_FPS = listOf(15, 20, 24, 25, 30, 50, 60)

        /**
         * Keeps only the modes whose frame rate is in [SELECTABLE_FPS]. If the card reports
         * nothing in that list at all, every mode is kept so there is still something to pick.
         */
        fun selectable(modes: List<VideoMode>): List<VideoMode> =
            modes.filter { it.fps in SELECTABLE_FPS }.ifEmpty { modes }

        /** Distinct resolutions, largest first (uncompressed before MJPEG at the same size). */
        fun resolutions(modes: List<VideoMode>): List<Resolution> =
            modes.map { it.resolution }.distinct().sortedWith(
                compareByDescending<Resolution> { it.width * it.height }.thenBy { it.isMjpeg }
            )

        /** Flattens the camera's format descriptors into one entry per resolution + fps. */
        fun buildModes(formats: List<Format>?): List<VideoMode> {
            val result = ArrayList<VideoMode>()
            if (formats == null) return result
            for (format in formats) {
                val descriptors = format.frameDescriptors ?: continue
                for (d in descriptors) {
                    if (d.type != UVCCamera.UVC_VS_FRAME_MJPEG &&
                        d.type != UVCCamera.UVC_VS_FRAME_UNCOMPRESSED
                    ) continue
                    val fpsValues = ArrayList<Int>()
                    val intervals = d.intervals
                    if (intervals != null) {
                        for (i in intervals) fpsValues.add(i.fps)
                    }
                    if (fpsValues.isEmpty() && d.fps > 0) fpsValues.add(d.fps)
                    val distinct = fpsValues.distinct()
                    for (fps in distinct) {
                        result.add(VideoMode(d.type, d.width, d.height, fps, distinct))
                    }
                }
            }
            return result
        }

        /**
         * Picks the best default: the largest picture up to 1080p that still runs at
         * 24 fps or more, then the highest frame rate (60 beats 30), and uncompressed
         * over MJPEG when everything else is equal.
         */
        fun pickBest(modes: List<VideoMode>): VideoMode? {
            if (modes.isEmpty()) return null
            val upTo1080 = modes.filter { it.height <= 1080 }.ifEmpty { modes }
            val smooth = upTo1080.filter { it.fps >= 24 }.ifEmpty { upTo1080 }
            return smooth.maxWithOrNull(
                compareBy<VideoMode>(
                    { it.width * it.height },
                    { minOf(it.fps, 60) },
                    { if (it.isMjpeg) 0 else 1 },
                    { -it.fps }
                )
            )
        }
    }
}
