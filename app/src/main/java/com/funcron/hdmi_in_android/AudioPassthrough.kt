package com.example.hdmimonitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Plays the capture card's USB audio input through the phone's current output.
 * Many capture cards expose HDMI audio as a USB microphone (usually 48 kHz stereo).
 *
 * Quality measures, in order of how much they usually matter:
 *  - Capture from an unprocessed source when the phone has one. The plain MIC source
 *    often applies automatic gain, noise suppression and voice filtering, which wrecks
 *    music and game audio.
 *  - Use the sample rate and channel count the card actually offers, so Android does not
 *    insert a resampler or a mono/stereo conversion.
 *  - Run the audio thread at audio priority, so scheduling delays do not cause dropouts.
 *  - The capture card and the phone's speaker run on different clocks. Left alone, one
 *    slowly outruns the other and you get a click or dropout every few seconds. The
 *    playback buffer fill is watched and corrected by adding or removing a single sample
 *    now and then, which is inaudible.
 *  - If playback still underruns, the buffer target grows by itself (a little more delay,
 *    no more dropouts).
 */
class AudioPassthrough {

    @Volatile
    private var running = false
    private var thread: Thread? = null

    val isRunning: Boolean
        get() = running

    private class Streams(
        val recorder: AudioRecord,
        val track: AudioTrack,
        val sampleRate: Int,
        val channels: Int,
        val source: Int
    )

    /** Returns false if there is no USB audio input or setup failed. */
    fun start(context: Context): Boolean {
        if (running) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return false

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val usbInput = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        } ?: return false

        val streams = try {
            createStreams(audioManager, usbInput)
        } catch (e: Throwable) {
            Log.w(TAG, "Audio setup failed", e)
            null
        } ?: return false

        running = true
        thread = Thread({ pump(streams) }, "hdmi-audio").also { it.start() }
        return true
    }

    fun stop() {
        running = false
        try {
            thread?.join(500)
        } catch (e: InterruptedException) {
        }
        thread = null
    }

    // ------------------------------------------------------------------ setup

    /** Prefers 48 kHz stereo, otherwise whatever the card offers. */
    private fun pickRate(device: AudioDeviceInfo): Int {
        val rates = device.sampleRates
        return when {
            rates.isEmpty() || 48000 in rates -> 48000
            44100 in rates -> 44100
            else -> rates.max()
        }
    }

    private fun pickChannels(device: AudioDeviceInfo): Int {
        val counts = device.channelCounts
        return if (counts.isEmpty() || 2 in counts) 2 else 1
    }

    private fun createStreams(audioManager: AudioManager, usbInput: AudioDeviceInfo): Streams? {
        val sampleRate = pickRate(usbInput)
        val channels = pickChannels(usbInput)
        val inMask = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val outMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val frameBytes = channels * 2
        val chunkFrames = sampleRate / 100                 // 10 ms per read

        val minIn = AudioRecord.getMinBufferSize(sampleRate, inMask, encoding)
        val minOut = AudioTrack.getMinBufferSize(sampleRate, outMask, encoding)
        if (minIn <= 0 || minOut <= 0) return null

        // Sources in order of preference. UNPROCESSED has no gain control or filtering;
        // VOICE_RECOGNITION is the next least-processed; MIC is the last resort.
        val sources = ArrayList<Int>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        ) {
            sources.add(MediaRecorder.AudioSource.UNPROCESSED)
        }
        sources.add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        sources.add(MediaRecorder.AudioSource.MIC)

        var recorder: AudioRecord? = null
        var usedSource = -1
        for (source in sources) {
            try {
                val rec = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(sampleRate)
                            .setChannelMask(inMask)
                            .build()
                    )
                    // room for 80 ms so a late wake-up does not overrun the capture buffer
                    .setBufferSizeInBytes(maxOf(minIn * 2, chunkFrames * frameBytes * 8))
                    .build()
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    recorder = rec
                    usedSource = source
                    break
                }
                rec.release()
            } catch (e: Throwable) {
                Log.w(TAG, "Audio source $source not usable", e)
            }
        }
        if (recorder == null) return null
        recorder.setPreferredDevice(usbInput)

        val trackBuilder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(outMask)
                    .build()
            )
            // capacity for 80 ms; the part actually used starts small and grows on demand
            .setBufferSizeInBytes(maxOf(minOut, chunkFrames * frameBytes * 8))
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        val track = trackBuilder.build()

        Log.i(TAG, "audio: source=$usedSource rate=$sampleRate channels=$channels")
        return Streams(recorder, track, sampleRate, channels, usedSource)
    }

    // ------------------------------------------------------------------- loop

    private fun pump(s: Streams) {
        val recorder = s.recorder
        val track = s.track
        val channels = s.channels
        val chunkFrames = s.sampleRate / 100                 // 10 ms
        val chunkShorts = chunkFrames * channels

        // one spare frame so a sample can be added when the buffer runs low
        val chunk = ShortArray(chunkShorts + channels)

        val capacityFrames = track.bufferCapacityInFrames
        val deadband = chunkFrames / 2                       // +-5 ms before correcting
        val maxTarget = minOf(chunkFrames * 6, capacityFrames - chunkFrames * 3)
        // fill (frames queued in the track, measured just before each write) to aim for
        var targetFill = minOf(chunkFrames * 2, maxTarget)

        fun applyBufferSize() {
            track.setBufferSizeInFrames(minOf(capacityFrames, targetFill + chunkFrames * 4))
        }

        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (e: Throwable) {
        }

        try {
            applyBufferSize()
            // start with the target amount of silence queued, so playback has headroom
            var written = 0L
            val silence = ShortArray(targetFill * channels)
            written += track.write(silence, 0, silence.size) / channels
            track.play()
            recorder.startRecording()

            var underruns = track.underrunCount
            var corrections = 0
            var lastLog = System.nanoTime()

            while (running) {
                // read exactly one 10 ms chunk
                var got = 0
                while (got < chunkShorts && running) {
                    val n = recorder.read(chunk, got, chunkShorts - got)
                    if (n < 0) return
                    got += n
                }
                if (!running) break

                // Where is playback? head position counts frames actually played (32-bit wrap).
                val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                val fill = written - head

                var frames = chunkFrames
                if (fill < targetFill - deadband) {
                    // playback is running ahead of capture: repeat the last sample once
                    for (c in 0 until channels) {
                        chunk[chunkFrames * channels + c] = chunk[(chunkFrames - 1) * channels + c]
                    }
                    frames = chunkFrames + 1
                    corrections++
                } else if (fill > targetFill + deadband) {
                    // capture is running ahead of playback: merge the last two samples into one
                    for (c in 0 until channels) {
                        val a = chunk[(chunkFrames - 2) * channels + c].toInt()
                        val b = chunk[(chunkFrames - 1) * channels + c].toInt()
                        chunk[(chunkFrames - 2) * channels + c] = ((a + b) / 2).toShort()
                    }
                    frames = chunkFrames - 1
                    corrections++
                }

                val w = track.write(chunk, 0, frames * channels)
                if (w < 0) return
                written += w / channels

                // Real underruns (not drift) mean the buffer is too small for this phone:
                // keep a little more in reserve from now on.
                val u = track.underrunCount
                if (u > underruns) {
                    underruns = u
                    if (targetFill < maxTarget) {
                        targetFill = minOf(targetFill + chunkFrames / 2, maxTarget)
                        applyBufferSize()
                        Log.i(TAG, "underrun: buffer target now ${targetFill * 1000 / s.sampleRate} ms")
                    }
                }

                val now = System.nanoTime()
                if (now - lastLog > 10_000_000_000L) {
                    Log.i(
                        TAG, "audio ok: fill=${fill * 1000 / s.sampleRate} ms " +
                            "target=${targetFill * 1000 / s.sampleRate} ms " +
                            "underruns=$underruns drift-corrections/10s=$corrections"
                    )
                    corrections = 0
                    lastLog = now
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Audio loop ended", e)
            // Audio is best-effort; video keeps running.
        } finally {
            try {
                recorder.stop()
            } catch (e: Throwable) {
            }
            recorder.release()
            try {
                track.stop()
            } catch (e: Throwable) {
            }
            track.release()
            running = false
        }
    }

    companion object {
        private const val TAG = "HdmiAudio"
    }
}
