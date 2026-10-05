package vn.pdscanner.sdr

import kotlin.concurrent.thread

class PdScanEngine(
    private val client: RtlTcpClient,
    private val listener: Listener
) {
    interface Listener {
        fun onBusy(busy: Boolean)
        fun onMessage(text: String)
        fun onBandResult(freqHz: Long, metrics: BandMetrics, score: Int)
        fun onOverall(score: Int, hotBands: Int)
        fun onBaselineReady()
        fun onError(text: String)
    }

    @Volatile private var cancelled = false
    private val baseline = linkedMapOf<Long, BaselineMetrics>()

    fun hasBaseline(): Boolean = baseline.isNotEmpty()

    fun stop() {
        cancelled = true
    }

    fun captureBaseline(frequenciesHz: List<Long>) {
        runJob {
            listener.onMessage("Đang lấy nền RF trong khoảng 5 giây...")
            val buckets = frequenciesHz.associateWith { mutableListOf<BandMetrics>() }

            val rounds = 3
            repeat(rounds) { round ->
                for (freq in frequenciesHz) {
                    checkCancelled()
                    listener.onMessage("Lấy nền ${freq / 1_000_000.0} MHz  (${round + 1}/$rounds)")
                    val m = captureBand(freq, 260)
                    buckets.getValue(freq).add(m)
                    listener.onBandResult(freq, m, 0)
                }
            }

            baseline.clear()
            for ((freq, list) in buckets) {
                baseline[freq] = PdAnalyzer.averageBaseline(list)
            }

            listener.onBaselineReady()
            listener.onMessage("Đã lưu nền. Có thể QUÉT PD.")
        }
    }

    fun scan(frequenciesHz: List<Long>, seconds: Int = 10) {
        runJob {
            val start = System.currentTimeMillis()
            val latestScores = linkedMapOf<Long, Int>()
            var cycle = 0

            listener.onMessage(
                if (hasBaseline()) "Đang quét và so sánh với nền..."
                else "Đang quét không dùng nền..."
            )

            while (!cancelled && System.currentTimeMillis() - start < seconds * 1000L) {
                cycle++
                for (freq in frequenciesHz) {
                    checkCancelled()
                    listener.onMessage("Quét ${freq / 1_000_000.0} MHz • vòng $cycle")
                    val m = captureBand(freq, 240)
                    val score = PdAnalyzer.score(m, baseline[freq])
                    latestScores[freq] = score
                    listener.onBandResult(freq, m, score)
                }

                val scores = latestScores.values.sortedDescending()
                if (scores.isNotEmpty()) {
                    val max = scores.first()
                    val topAvg = scores.take(minOf(3, scores.size)).average()
                    val hotBands = scores.count { it >= 55 }

                    var overall = (0.60 * max + 0.40 * topAvg).toInt()
                    if (hotBands >= 2) overall += 8
                    if (hotBands == 1 && max >= 65) overall -= 4
                    overall = overall.coerceIn(0, 100)

                    listener.onOverall(overall, hotBands)
                }
            }

            listener.onMessage("Đã hoàn thành lượt quét.")
        }
    }

    /**
     * Continuous multi-band monitoring. Runs until stop() is pressed.
     *
     * RTL-SDR can only tune one center frequency at a time, so "realtime"
     * here means continuous round-robin monitoring of the selected bands.
     * With fewer bands the refresh rate is faster.
     */
    fun realtime(frequenciesHz: List<Long>) {
        runJob {
            val latestScores = linkedMapOf<Long, Int>()
            var smoothedOverall: Double? = null
            var cycle = 0

            listener.onMessage(
                if (hasBaseline()) "REALTIME: đang theo dõi liên tục và so sánh với nền..."
                else "REALTIME: đang theo dõi liên tục..."
            )

            while (!cancelled) {
                cycle++

                for (freq in frequenciesHz) {
                    checkCancelled()

                    listener.onMessage(
                        "REALTIME • ${freq / 1_000_000.0} MHz • vòng $cycle"
                    )

                    // Shorter capture window than the 10-second scan for better responsiveness.
                    val m = captureBand(freq, 150)
                    val score = PdAnalyzer.score(m, baseline[freq])
                    latestScores[freq] = score
                    listener.onBandResult(freq, m, score)
                }

                val scores = latestScores.values.sortedDescending()
                if (scores.isNotEmpty()) {
                    val max = scores.first()
                    val topAvg = scores.take(minOf(3, scores.size)).average()
                    val hotBands = scores.count { it >= 55 }

                    var instant = (0.60 * max + 0.40 * topAvg)
                    if (hotBands >= 2) instant += 8.0
                    if (hotBands == 1 && max >= 65) instant -= 4.0
                    instant = instant.coerceIn(0.0, 100.0)

                    // EMA prevents the traffic-light display from flickering too much.
                    smoothedOverall =
                        if (smoothedOverall == null) instant
                        else 0.65 * smoothedOverall!! + 0.35 * instant

                    listener.onOverall(
                        smoothedOverall!!.toInt().coerceIn(0, 100),
                        hotBands
                    )
                }
            }
        }
    }

    private fun captureBand(freqHz: Long, windowMs: Int): BandMetrics {
        client.setFrequency(freqHz)

        // Let the tuner settle, then throw away stale samples from the previous frequency.
        Thread.sleep(100)
        client.clearBufferedIq()
        try {
            client.readWindow(70) // discard a short settling window
        } catch (_: Exception) {}
        client.clearBufferedIq()

        val iq = client.readWindow(windowMs)
        return PdAnalyzer.analyze(iq, client.sampleRate)
    }

    private fun runJob(block: () -> Unit) {
        cancelled = false
        thread(name = "pd-scan") {
            listener.onBusy(true)
            try {
                block()
            } catch (_: ScanCancelled) {
                listener.onMessage("Đã dừng.")
            } catch (e: Exception) {
                listener.onError(e.message ?: "Lỗi không xác định")
            } finally {
                listener.onBusy(false)
            }
        }
    }

    private fun checkCancelled() {
        if (cancelled) throw ScanCancelled()
        if (!client.isConnected) throw IllegalStateException("SDR đã mất kết nối")
    }

    private class ScanCancelled : RuntimeException()
}
