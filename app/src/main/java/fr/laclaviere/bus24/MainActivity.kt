package fr.laclaviere.bus24

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import fr.laclaviere.bus24.data.GtfsData
import fr.laclaviere.bus24.data.GtfsRt
import fr.laclaviere.bus24.data.GtfsStore
import fr.laclaviere.bus24.data.NetworkSources
import fr.laclaviere.bus24.data.NextBusService
import fr.laclaviere.bus24.data.Passage
import fr.laclaviere.bus24.data.TripUpdatesClient
import fr.laclaviere.bus24.widget.Bus24Refresher
import java.time.ZoneId
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val zone: ZoneId = ZoneId.of("Europe/Paris")
    private var lastData: GtfsData? = null
    private lateinit var container: LinearLayout
    private lateinit var statusView: TextView
    private var resumeMessage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        container = findViewById(R.id.passage_container)
        statusView = findViewById(R.id.status)
        findViewById<Button>(R.id.refresh_button).setOnClickListener { refresh() }
        refresh()
    }

    private fun refresh() {
        thread(name = "bus24-refresh") {
            val result = try {
                loadPassages()
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                result.fold(
                    onSuccess = ::render,
                    onFailure = { e ->
                        statusView.text = getString(R.string.error, e.message ?: "?")
                        Toast.makeText(this, R.string.error_network, Toast.LENGTH_LONG).show()
                    },
                )
            }
            Bus24Refresher.scheduleNext(this)
        }
    }

    private fun loadPassages(): Result<Pair<List<Passage>, String>> {
        val store = GtfsStore(cacheDir)
        val hadCache = store.isCachePresent
        store.refresh()
        val gtfs = store.load() ?: return Result.failure(IllegalStateException("cache GTFS vide"))
        lastData = gtfs
        val nowSec = System.currentTimeMillis() / 1000

        var rtMap: Map<String, fr.laclaviere.bus24.data.TripUpdateInfo> = emptyMap()
        var rtOk = false
        try {
            rtMap = GtfsRt.parseFeed(TripUpdatesClient.fetch()).associateBy { it.tripId }
            rtOk = true
        } catch (_: Exception) {
            if (!hadCache && !store.isCachePresent) {
                throw IllegalStateException("GTFS et temps réel indisponibles")
            }
        }

        val passages = NextBusService().nextPassages(
            gtfs, NetworkSources.ROUTE_ID, NetworkSources.STOP_ID, nowSec, zone, 8, rtMap,
        )
        if (passages.isEmpty()) {
            val resume = NextBusService.nextServiceDay(
                gtfs, nowSec, zone, NetworkSources.ROUTE_ID, NetworkSources.STOP_ID,
            )
            resumeMessage =
                if (resume != null) getString(R.string.service_resumes, resume)
                else getString(R.string.no_more_today)
        } else {
            resumeMessage = null
        }
        val status = buildString {
            append(getString(R.string.gtfs_age))
            append(" ")
            store.gtfsAgeMs()?.let { append(it / 3_600_000).append(" h") } ?: append("—")
            append(" • ")
            append(getString(if (rtOk) R.string.rt_ok else R.string.rt_missing))
        }
        return Result.success(passages to status)
    }

    private fun render(result: Pair<List<Passage>, String>) {
        val (passages, status) = result
        container.removeAllViews()
        statusView.text = status
        val nowSec = System.currentTimeMillis() / 1000
        if (passages.isEmpty()) {
            container.addView(
                passageView(resumeMessage ?: getString(R.string.no_more_today), Color.BLACK, 22f),
            )
        } else {
            passages.forEachIndexed { index, passage ->
                val label = "${NextBusService.formatTime(passage.epochUtc, zone)}  •  " +
                    (if (passage.cancelled) getString(R.string.cancelled)
                    else NextBusService.formatMinutes(passage.minutesAway(nowSec)))
                val color = when {
                    passage.isAlert(nowSec) -> COLOR_RED
                    passage.realtime -> COLOR_BLUE
                    else -> Color.BLACK
                }
                val prefix = if (index == 0) "★ " else "• "
                container.addView(passageView(prefix + label, color, if (index == 0) 30f else 20f))
            }
        }
    }

    private fun passageView(text: String, color: Int, sizeSp: Float): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(color)
        tv.textSize = sizeSp
        tv.setPadding(0, dp(14), 0, dp(14))
        tv.gravity = Gravity.CENTER
        return tv
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        val COLOR_RED: Int get() = Color.rgb(0xD3, 0x2F, 0x2F)
        val COLOR_BLUE: Int get() = Color.rgb(0x21, 0x65, 0xC0)
    }
}
