package com.alphasteg.pro.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Sends vault playback out through an attached USB DAC.
 *
 * The whole point of hiding the vault inside FLAC carriers is that it lives on
 * gear built for music, so a decrypted track should come out of the good DAC
 * rather than the phone speaker. The DAC stays *completely unmodified*: it
 * enumerates as a standard USB audio class device, Android's own driver claims
 * it, and we do nothing but express a routing preference. Nothing is installed
 * on the DAC, and nothing here is specific to HiBy — a M500, a dongle, or a
 * desktop amp all land in the same code path.
 *
 * Scope worth being honest about: this is Android's ordinary media path, so the
 * framework mixer still owns sample-rate conversion. [Dac.summary] reports what
 * the hardware says it can accept — it is not a claim that we are feeding it
 * that rate. Bit-perfect output would mean driving the USB interface directly
 * and bypassing the mixer, which is a separate exercise.
 */
object AudioOutput {

    /** An attached USB audio output, and what it reports it can accept. */
    data class Dac(
        val id: Int,
        val name: String,
        /** True for a USB-C headphone dongle, false for a self-powered DAC. */
        val isHeadset: Boolean,
        val sampleRates: List<Int>,
        val channelCounts: List<Int>,
        val encodings: List<Int>
    ) {
        /** e.g. "HiBy M500 - up to 384 kHz - 32-bit". */
        val summary: String
            get() = buildString {
                append(name)
                sampleRates.maxOrNull()?.let { append(" - up to ${formatRate(it)}") }
                depthLabel()?.let { append(" - $it") }
            }

        /** The widest PCM depth the device advertises, or null if it says nothing. */
        private fun depthLabel(): String? {
            // Ordered widest-first so the first hit is the best the device takes.
            val known = listOf(
                AudioFormat.ENCODING_PCM_FLOAT to "32-bit float",
                AudioFormat.ENCODING_PCM_32BIT to "32-bit",
                AudioFormat.ENCODING_PCM_24BIT_PACKED to "24-bit",
                AudioFormat.ENCODING_PCM_16BIT to "16-bit"
            )
            return known.firstOrNull { encodings.contains(it.first) }?.second
        }
    }

    /**
     * The USB DAC currently attached, or null when audio would go to the phone's
     * own output. A self-powered DAC wins over a dongle if somehow both are on.
     */
    fun current(context: Context): Dac? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val outputs = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull() ?: return null
        val usb = outputs.filter { it.type in USB_TYPES }
        val best = usb.minByOrNull { if (it.type == AudioDeviceInfo.TYPE_USB_DEVICE) 0 else 1 } ?: return null
        return best.toDac()
    }

    /**
     * Ask [player] to come out of [dac]. Returns true if the framework accepted
     * the request. Failure is not fatal — Android already routes media to USB
     * automatically — so callers can treat this as a best-effort pin.
     */
    fun pin(player: MediaPlayer, context: Context, dac: Dac? = current(context)): Boolean {
        if (dac == null) return false
        // MediaPlayer only implements AudioRouting from API 28; below that we
        // rely on the framework's own automatic routing to USB.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val info = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull()
            ?.firstOrNull { it.id == dac.id } ?: return false
        return runCatching { player.setPreferredDevice(info) }.getOrDefault(false)
    }

    /**
     * Watch for DACs being plugged and unplugged, so a viewer can relabel itself
     * mid-track. Returns the registered callback; hand it back to [stopWatching]
     * to avoid leaking the listener when the screen goes away.
     */
    fun watch(context: Context, onChange: (Dac?) -> Unit): AudioDeviceCallback? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = onChange(current(context))
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = onChange(current(context))
        }
        runCatching { am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper())) }
        return cb
    }

    fun stopWatching(context: Context, cb: AudioDeviceCallback?) {
        if (cb == null) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching { am.unregisterAudioDeviceCallback(cb) }
    }

    /** One line naming where sound is going, for the viewer's status text. */
    fun describe(context: Context): String =
        current(context)?.let { "Out: ${it.summary}" } ?: "Out: this device"

    private val USB_TYPES = setOf(
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY
    )

    private fun AudioDeviceInfo.toDac(): Dac = Dac(
        id = id,
        // productName is usually the USB product string ("M500"); fall back to
        // something readable rather than showing an empty label.
        name = productName?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: "USB DAC",
        isHeadset = type == AudioDeviceInfo.TYPE_USB_HEADSET,
        sampleRates = sampleRates.toList(),
        channelCounts = channelCounts.toList(),
        encodings = encodings.toList()
    )

    private fun formatRate(hz: Int): String {
        val khz = hz / 1000.0
        return if (khz % 1.0 == 0.0) "${khz.toInt()} kHz" else "%.1f kHz".format(khz)
    }
}
