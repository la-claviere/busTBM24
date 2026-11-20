package fr.laclaviere.bus24.widget

import android.content.Context
import java.util.Properties

/** État persisté entre les ticks du widget (dernier calcul, fraîcheur des données). */
data class Bus24State(
    val primaryEpoch: Long?,
    val message: String,
    val subMessage: String,
    val status: String,
    val blink: Boolean,
    val realtime: Boolean,
    val cancelled: Boolean,
    val fetchedAtMs: Long,
) {
    companion object {
        private const val FILE = "bus24_widget_state"

        fun save(context: Context, state: Bus24State) {
            val props = Properties()
            props.setProperty("primaryEpoch", state.primaryEpoch?.toString() ?: "")
            props.setProperty("message", state.message)
            props.setProperty("subMessage", state.subMessage)
            props.setProperty("status", state.status)
            props.setProperty("blink", state.blink.toString())
            props.setProperty("realtime", state.realtime.toString())
            props.setProperty("cancelled", state.cancelled.toString())
            props.setProperty("fetchedAtMs", state.fetchedAtMs.toString())
            props.store(context.openFileOutput(FILE, Context.MODE_PRIVATE), null)
        }

        fun load(context: Context): Bus24State? {
            val props = Properties()
            context.openFileInput(FILE).use { props.load(it) }
            val fetchedAt = props.getProperty("fetchedAtMs")?.toLongOrNull() ?: return null
            return Bus24State(
                primaryEpoch = props.getProperty("primaryEpoch")?.toLongOrNull(),
                message = props.getProperty("message") ?: "",
                subMessage = props.getProperty("subMessage") ?: "",
                status = props.getProperty("status") ?: "",
                blink = props.getProperty("blink") == "true",
                realtime = props.getProperty("realtime") == "true",
                cancelled = props.getProperty("cancelled") == "true",
                fetchedAtMs = fetchedAt,
            )
        }

        /** Le service est-il proche ou en cours ? (aucun passage calculé = service fini) */
        fun serviceActiveSoon(context: Context): Boolean {
            val loaded = load(context) ?: return true
            val primary = loaded.primaryEpoch ?: return true
            val nowSec = System.currentTimeMillis() / 1000
            return primary - nowSec < NEXT_HORIZON_SEC
        }

        const val NEXT_HORIZON_SEC = 45L * 60
    }
}
