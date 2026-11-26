package com.vphone.os.mic

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Real-time Voice Enhancement & Male-to-Female Voice Changer DSP Engine
 * integrated directly from ClearVoice Live v1.1.
 */
class VoiceDspProcessor(private val sampleRate: Float = 48000f) {

    private class BiquadFilter {
        var b0 = 1f; var b1 = 0f; var b2 = 0f
        var a1 = 0f; var a2 = 0f
        private var z1 = 0f; private var z2 = 0f

        fun process(input: Float): Float {
            val output = b0 * input + z1
            z1 = b1 * input - a1 * output + z2
            z2 = b2 * input - a2 * output
            return output
        }

        fun setHighPass(freq: Float, q: Float, fs: Float) {
            val w0 = (2.0 * PI * freq / fs)
            val cosW = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            b0 = ((1.0 + cosW) / 2.0 / a0).toFloat()
            b1 = (-(1.0 + cosW) / a0).toFloat()
            b2 = ((1.0 + cosW) / 2.0 / a0).toFloat()
            a1 = (-2.0 * cosW / a0).toFloat()
            a2 = ((1.0 - alpha) / a0).toFloat()
        }

        fun setLowPass(freq: Float, q: Float, fs: Float) {
            val w0 = (2.0 * PI * freq / fs)
            val cosW = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            b0 = ((1.0 - cosW) / 2.0 / a0).toFloat()
            b1 = ((1.0 - cosW) / a0).toFloat()
            b2 = ((1.0 - cosW) / 2.0 / a0).toFloat()
            a1 = (-2.0 * cosW / a0).toFloat()
            a2 = ((1.0 - alpha) / a0).toFloat()
        }

        fun setPeakingEQ(freq: Float, q: Float, gainDb: Float, fs: Float) {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = (2.0 * PI * freq / fs)
            val cosW = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha / a
            b0 = ((1.0 + alpha * a) / a0).toFloat()
            b1 = ((-2.0 * cosW) / a0).toFloat()
            b2 = ((1.0 - alpha * a) / a0).toFloat()
            a1 = ((-2.0 * cosW) / a0).toFloat()
            a2 = ((1.0 - alpha / a) / a0).toFloat()
        }
    }

    private class LivePitchShifter {
        private val bufferSize = 4096
        private val bufferMask = bufferSize - 1
        private val windowSamples = 1600f
        private val buffer = FloatArray(bufferSize)
        private var writeIdx = 0
        private var phase = 0f

        fun reset() {
            buffer.fill(0f)
            writeIdx = 0
            phase = 0f
        }

        fun process(input: Float, pitchRatio: Float): Float {
            buffer[writeIdx] = input

            if (abs(pitchRatio - 1.0f) < 0.005f) {
                writeIdx = (writeIdx + 1) and bufferMask
                return input
            }

            val p1 = phase
            var p2 = p1 + 0.5f
            if (p2 >= 1f) p2 -= 1f

            val d1 = p1 * windowSamples
            val d2 = p2 * windowSamples

            val s1 = readInterpolated(d1)
            val s2 = readInterpolated(d2)

            val g1 = (0.5 * (1.0 - cos(2.0 * PI * p1))).toFloat()
            val g2 = 1f - g1

            writeIdx = (writeIdx + 1) and bufferMask
            phase += (1f - pitchRatio) / windowSamples
            while (phase < 0f) phase += 1f
            while (phase >= 1f) phase -= 1f

            return s1 * g1 + s2 * g2
        }

        private fun readInterpolated(delay: Float): Float {
            var readPos = writeIdx - delay
            if (readPos < 0f) readPos += bufferSize
            val i0 = readPos.toInt()
            val frac = readPos - i0
            val idx0 = i0 and bufferMask
            val idx1 = (idx0 + 1) and bufferMask
            return buffer[idx0] * (1f - frac) + buffer[idx1] * frac
        }
    }

    private val highPass = BiquadFilter()
    private val warmthEq = BiquadFilter()
    private val clarityEq = BiquadFilter()
    private val harshnessSoftener = BiquadFilter()

    private val chestCutEq = BiquadFilter()
    private val formantMidEq = BiquadFilter()
    private val formantAirEq = BiquadFilter()
    private val pitchShifter = LivePitchShifter()

    private var lastClarity = -1f
    private var lastWarmth = -1f
    private var lastFeminineTone = -1f
    private var lastM2FEnabled = false
    private var currentGateGain = 1f

    init {
        highPass.setHighPass(85f, 0.707f, sampleRate)
        updateFilters(
            clarity = AudioEngineState.voiceClarity,
            warmth = AudioEngineState.soothingWarmth,
            m2fEnabled = AudioEngineState.maleToFemaleEnabled,
            feminineTone = AudioEngineState.feminineTone
        )
    }

    private fun updateFilters(
        clarity: Float,
        warmth: Float,
        m2fEnabled: Boolean,
        feminineTone: Float
    ) {
        if (m2fEnabled != lastM2FEnabled || abs(feminineTone - lastFeminineTone) > 0.01f) {
            if (m2fEnabled) {
                highPass.setHighPass(140f + feminineTone * 55f, 0.707f, sampleRate)
                chestCutEq.setPeakingEQ(185f, 1.0f, -(2f + feminineTone * 6f), sampleRate)
                formantMidEq.setPeakingEQ(1750f, 1.2f, 1f + feminineTone * 3.5f, sampleRate)
                formantAirEq.setPeakingEQ(5200f, 0.9f, 1f + feminineTone * 3.0f, sampleRate)
            } else {
                highPass.setHighPass(85f, 0.707f, sampleRate)
                pitchShifter.reset()
            }
            lastM2FEnabled = m2fEnabled
            lastFeminineTone = feminineTone
        }

        if (abs(clarity - lastClarity) > 0.01f) {
            clarityEq.setPeakingEQ(2800f, 1.1f, clarity * 9f, sampleRate)
            lastClarity = clarity
        }

        if (abs(warmth - lastWarmth) > 0.01f) {
            warmthEq.setPeakingEQ(210f, 0.8f, warmth * 4.5f, sampleRate)
            val cutoff = 12500f - (warmth * 5000f)
            harshnessSoftener.setLowPass(cutoff, 0.707f, sampleRate)
            lastWarmth = warmth
        }
    }

    fun processBuffer(samples: ShortArray, count: Int): Float {
        val gateSetting = AudioEngineState.noiseGateAmount
        val clarity = AudioEngineState.voiceClarity
        val warmth = AudioEngineState.soothingWarmth
        val boost = 1f + (AudioEngineState.micGainBoost * 3.0f)
        val m2fEnabled = AudioEngineState.maleToFemaleEnabled
        val feminineTone = AudioEngineState.feminineTone
        val pitchRatio = if (m2fEnabled) {
            2.0.pow(AudioEngineState.pitchSemitones / 12.0).toFloat()
        } else {
            1.0f
        }

        updateFilters(clarity, warmth, m2fEnabled, feminineTone)

        var sumSq = 0.0
        for (i in 0 until count) {
            val s = samples[i] / 32768f
            sumSq += s * s
        }
        val rms = sqrt(sumSq / count.coerceAtLeast(1)).toFloat()

        val threshold = 0.002f + (gateSetting * 0.043f)
        val targetGate = if (rms >= threshold || gateSetting < 0.02f) 1.0f else 0.06f
        val smoothCoeff = if (targetGate > currentGateGain) 0.02f else 0.0015f

        for (i in 0 until count) {
            currentGateGain += (targetGate - currentGateGain) * smoothCoeff

            var x = samples[i] / 32768f
            x = highPass.process(x)
            x *= currentGateGain

            if (m2fEnabled) {
                x = chestCutEq.process(x)
                x = pitchShifter.process(x, pitchRatio)
                x = formantMidEq.process(x)
                x = formantAirEq.process(x)
            } else {
                x = warmthEq.process(x)
            }

            x = clarityEq.process(x)
            x = harshnessSoftener.process(x)

            x = tanh((x * boost).toDouble()).toFloat()

            samples[i] = (x * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }

        return (rms * 5f).coerceIn(0f, 1f)
    }
}
