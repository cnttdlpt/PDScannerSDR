package vn.pdscanner.sdr

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

data class BandMetrics(
    val noiseDb: Double,
    val crestDb: Double,
    val p99AboveNoiseDb: Double,
    val pulseRate: Double,
    val kurtosis: Double
)

data class BaselineMetrics(
    val crestDb: Double,
    val p99AboveNoiseDb: Double,
    val pulseRate: Double,
    val kurtosis: Double
)

object PdAnalyzer {

    /**
     * Screening-oriented impulsive RF detector.
     * This intentionally does NOT claim to measure pC or certified PD magnitude.
     */
    fun analyze(iq: ByteArray, sampleRate: Int): BandMetrics {
        if (iq.size < 4096) throw IllegalArgumentException("Quá ít mẫu I/Q")

        // Keep CPU load reasonable on a phone.
        val complexSamples = iq.size / 2
        val stride = max(4, complexSamples / 35000)
        val count = max(128, complexSamples / stride)

        val db = DoubleArray(count)
        var pos = 0
        var s = 0
        while (s < complexSamples && pos < count) {
            val i = (iq[s * 2].toInt() and 0xFF) - 127.5
            val q = (iq[s * 2 + 1].toInt() and 0xFF) - 127.5
            val power = i * i + q * q + 1.0
            db[pos++] = 10.0 * log10(power)
            s += stride
        }

        val values = if (pos == db.size) db else db.copyOf(pos)
        values.sort()

        fun percentile(p: Double): Double {
            val idx = ((values.size - 1) * p).toInt().coerceIn(0, values.lastIndex)
            return values[idx]
        }

        val median = percentile(0.50)
        val p95 = percentile(0.95)
        val p99 = percentile(0.99)
        val peak = values.last()

        val threshold = max(median + 9.0, p95 + 2.0)

        // Count separated impulse events rather than every above-threshold sample.
        var events = 0
        var above = false
        var belowRun = 0
        for (v in valuesUnsorted(iq, stride, threshold)) {
            if (v) {
                if (!above) {
                    events++
                    above = true
                }
                belowRun = 0
            } else if (above) {
                belowRun++
                if (belowRun >= 3) {
                    above = false
                    belowRun = 0
                }
            }
        }

        val durationSec = complexSamples.toDouble() / sampleRate.toDouble()
        val pulseRate = if (durationSec > 0) events / durationSec else 0.0

        // Kurtosis of the sampled dB envelope. Normal Gaussian-like activity is around 3.
        val mean = values.average()
        var m2 = 0.0
        var m4 = 0.0
        for (v in values) {
            val d = v - mean
            val d2 = d * d
            m2 += d2
            m4 += d2 * d2
        }
        m2 /= values.size
        m4 /= values.size
        val kurt = if (m2 > 1e-9) m4 / (m2 * m2) else 3.0

        return BandMetrics(
            noiseDb = median,
            crestDb = peak - median,
            p99AboveNoiseDb = p99 - median,
            pulseRate = pulseRate,
            kurtosis = kurt.coerceAtMost(100.0)
        )
    }

    private fun valuesUnsorted(iq: ByteArray, stride: Int, threshold: Double): BooleanArray {
        val complexSamples = iq.size / 2
        val count = max(128, complexSamples / stride)
        val out = BooleanArray(count)
        var pos = 0
        var s = 0
        while (s < complexSamples && pos < count) {
            val i = (iq[s * 2].toInt() and 0xFF) - 127.5
            val q = (iq[s * 2 + 1].toInt() and 0xFF) - 127.5
            val power = i * i + q * q + 1.0
            val v = 10.0 * log10(power)
            out[pos++] = v >= threshold
            s += stride
        }
        return if (pos == out.size) out else out.copyOf(pos)
    }

    fun averageBaseline(items: List<BandMetrics>): BaselineMetrics {
        if (items.isEmpty()) return BaselineMetrics(0.0, 0.0, 0.0, 3.0)
        return BaselineMetrics(
            crestDb = items.map { it.crestDb }.average(),
            p99AboveNoiseDb = items.map { it.p99AboveNoiseDb }.average(),
            pulseRate = items.map { it.pulseRate }.average(),
            kurtosis = items.map { it.kurtosis }.average()
        )
    }

    fun score(current: BandMetrics, baseline: BaselineMetrics?): Int {
        fun clamp01(x: Double) = x.coerceIn(0.0, 1.0)

        // Absolute impulsiveness score: works even without a baseline.
        val absCrest = clamp01((current.crestDb - 11.0) / 18.0)
        val absP99 = clamp01((current.p99AboveNoiseDb - 4.5) / 10.0)
        val absKurt = clamp01((current.kurtosis - 3.0) / 12.0)
        val absPulse = clamp01(current.pulseRate / 140.0)
        val absoluteScore =
            35.0 * absCrest +
            30.0 * absP99 +
            20.0 * absKurt +
            15.0 * absPulse

        if (baseline == null) return absoluteScore.toInt().coerceIn(0, 100)

        // Relative-to-background score.
        val pulseRatio = ln((current.pulseRate + 5.0) / (baseline.pulseRate + 5.0)) / ln(4.0)
        val relPulse = clamp01(pulseRatio)
        val relCrest = clamp01((current.crestDb - baseline.crestDb) / 10.0)
        val relP99 = clamp01((current.p99AboveNoiseDb - baseline.p99AboveNoiseDb) / 7.0)
        val relKurt = clamp01((current.kurtosis - baseline.kurtosis) / 8.0)
        val relativeScore =
            40.0 * relPulse +
            25.0 * relCrest +
            20.0 * relP99 +
            15.0 * relKurt

        return (0.55 * absoluteScore + 0.45 * relativeScore)
            .toInt()
            .coerceIn(0, 100)
    }
}
