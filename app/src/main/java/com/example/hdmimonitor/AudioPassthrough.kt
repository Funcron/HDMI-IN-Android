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
import androidx.core.content.ContextCompat

/**
 * Plays the capture card's USB audio input through the phone's current output.
 * Many capture cards expose HDMI audio as a USB microphone (48 kHz stereo).
 */
class AudioPassthrough {

    @Volatile
    private var running = false
    private var thread: Thread? = null

    val isRunning: Boolean
        get() = running

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

        val sampleRate = 48000
        val inMask = AudioFormat.CHANNEL_IN_STEREO
        val outMask = AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val minIn = AudioRecord.getMinBufferSize(sampleRate, inMask, encoding)
        val minOut = AudioTrack.getMinBufferSize(sampleRate, outMask, encoding)
        if (minIn <= 0 || minOut <= 0) return false

        val streams: Pair<AudioRecord, AudioTrack> = try {
            val rec = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(inMask)
                        .build()
                )
                .setBufferSizeInBytes(minIn * 2)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return false
            }
            rec.setPreferredDevice(usbInput)

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
                .setBufferSizeInBytes(minOut * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            Pair(rec, trackBuilder.build())
        } catch (e: Throwable) {
            return false
        }
        val recorder = streams.first
        val track = streams.second

        running = true
        thread = Thread({
            try {
                recorder.startRecording()
                track.play()
                val buffer = ByteArray(minIn)
                while (running) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        track.write(buffer, 0, read)
                    } else if (read < 0) {
                        break
                    }
                }
            } catch (e: Throwable) {
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
        }, "hdmi-audio").also { it.start() }
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
}
