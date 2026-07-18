package com.voxenai.cinecam.encoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Serializes the video and audio encoder threads' access to a single [MediaMuxer]. MediaMuxer
 * requires every track to be added via [MediaMuxer.addTrack] *before* [MediaMuxer.start] is
 * called, and the two encoders discover their output format independently and asynchronously,
 * so this buffers "not started yet" writes by simply dropping them (which only ever affects the
 * codec-config buffers emitted in the first few milliseconds, before either track has real
 * frame data to write).
 */
class MuxerWrapper private constructor(private val muxer: MediaMuxer, private val expectedTrackCount: Int) {
    constructor(outputPath: String, expectedTrackCount: Int) : this(
        MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4), expectedTrackCount
    )

    /** Writes into an already-open, writable file descriptor (used for MediaStore-backed output on API 29+). */
    constructor(fd: FileDescriptor, expectedTrackCount: Int) : this(
        MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4), expectedTrackCount
    )

    private val lock = Any()
    private var addedTracks = 0
    private var started = false
    private var released = false

    fun addVideoTrack(format: MediaFormat): Int = addTrack(format)
    fun addAudioTrack(format: MediaFormat): Int = addTrack(format)

    private fun addTrack(format: MediaFormat): Int = synchronized(lock) {
        val index = muxer.addTrack(format)
        addedTracks++
        if (addedTracks >= expectedTrackCount && !started) {
            muxer.start()
            started = true
        }
        index
    }

    fun writeSampleData(trackIndex: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (started && !released) {
                muxer.writeSampleData(trackIndex, buffer, info)
            }
        }
    }

    fun stopAndRelease() {
        synchronized(lock) {
            if (released) return
            if (started) {
                runCatching { muxer.stop() }
            }
            muxer.release()
            released = true
        }
    }
}
