package com.autoredial.pro.audio

import kotlin.math.cos
import kotlin.math.sqrt

class AudioToneClassifier {

    enum class ToneType {
        SILENCE,
        RINGBACK_CADENCE,
        BUSY_OR_REORDER,
        HUMAN_VOICE_OR_IVR
    }

    private val freq440 = 440.0
    private val freq480 = 480.0
    private val freq620 = 620.0
    private val freqVoiceMid1 = 1200.0
    private val freqVoiceMid2 = 2400.0

    fun classifyAudioChunk(buffer: ShortArray, length: Int, sampleRate: Int): ToneType {
        if (length == 0) return ToneType.SILENCE

        var sumSquares = 0.0
        for (i in 0 until length) {
            sumSquares += buffer[i] * buffer[i]
        }
        val rms = sqrt(sumSquares / length)
        if (rms < 120.0) return ToneType.SILENCE

        val power440 = goertzelMagnitude(buffer, length, sampleRate, freq440)
        val power480 = goertzelMagnitude(buffer, length, sampleRate, freq480)
        val power620 = goertzelMagnitude(buffer, length, sampleRate, freq620)
        val powerVoiceMid1 = goertzelMagnitude(buffer, length, sampleRate, freqVoiceMid1)
        val powerVoiceMid2 = goertzelMagnitude(buffer, length, sampleRate, freqVoiceMid2)

        val ringPower = power440 + power480
        val voicePower = powerVoiceMid1 + powerVoiceMid2
        val busyPower = power480 + power620

        val ringRatio = ringPower / (rms * rms + 1.0)
        val voiceRatio = voicePower / (rms * rms + 1.0)

        if (ringRatio > 0.45 && voiceRatio < 0.20) {
            return ToneType.RINGBACK_CADENCE
        }

        if ((busyPower / (rms * rms + 1.0)) > 0.50) {
            return ToneType.BUSY_OR_REORDER
        }

        return ToneType.HUMAN_VOICE_OR_IVR
    }

    private fun goertzelMagnitude(buffer: ShortArray, numSamples: Int, sampleRate: Int, targetFreq: Double): Double {
        val k = (0.5 + ((numSamples.toDouble() * targetFreq) / sampleRate)).toInt()
        val omega = (2.0 * Math.PI * k) / numSamples
        val cosine = cos(omega)
        val coeff = 2.0 * cosine

        var q0: Double
        var q1 = 0.0
        var q2 = 0.0

        for (i in 0 until numSamples) {
            q0 = coeff * q1 - q2 + buffer[i]
            q2 = q1
            q1 = q0
        }

        val real = q1 - q2 * cosine
        val imag = q2 * kotlin.math.sin(omega)
        return (real * real + imag * imag) / (numSamples * numSamples)
    }
}
