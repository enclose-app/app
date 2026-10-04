package io.app.enclose.wear

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import io.app.enclose.watchlink.WatchClaims
import io.app.enclose.watchlink.WatchLink
import io.app.enclose.watchlink.WatchMap
import io.app.enclose.watchlink.WatchStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What the watch knows of the phone. */
sealed interface LinkState {
    /** Nothing heard yet: the phone app hasn't run since being paired. */
    data object Waiting : LinkState

    /** Something heard, in a shape this watch app can't read: versions differ. */
    data object Unreadable : LinkState

    data class Known(val status: WatchStatus) : LinkState
}

/**
 * The watch's half of the Data Layer. Lives as long as the activity is
 * started — [attach]/[detach] — since a listener with no screen to update is
 * only a drain; [StatusListenerService] covers the closed-app case.
 */
class PhoneLink(context: Context) : DataClient.OnDataChangedListener {

    private val context = context.applicationContext
    private val dataClient = Wearable.getDataClient(this.context)

    private val _state = MutableStateFlow<LinkState>(LinkState.Waiting)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    /**
     * False until the first read of the phone's status has come back. Until
     * then [state] is [LinkState.Waiting] for want of an answer, not because
     * there is no phone — the screen shows the launch logo rather than saying so.
     */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** The walk's path and position; null until the phone has sent one. */
    private val _map = MutableStateFlow<WatchMap?>(null)
    val map: StateFlow<WatchMap?> = _map.asStateFlow()

    /** Claims near the walk; null until the phone has sent them. */
    private val _claims = MutableStateFlow<WatchClaims?>(null)
    val claims: StateFlow<WatchClaims?> = _claims.asStateFlow()

    fun attach() {
        dataClient.addListener(this)
        // The listener hears changes only; what the phone said before the app
        // opened is read once here. Any node: there is one phone.
        for (path in listOf(WatchLink.STATUS_PATH, WatchLink.MAP_PATH, WatchLink.CLAIMS_PATH)) {
            dataClient.getDataItems(
                Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(path).build(),
            ).addOnSuccessListener { items ->
                items.lastOrNull()?.let(::accept)
                items.release()
            }.addOnCompleteListener {
                // Failed reads count too: "no answer" is an answer, and the
                // logo must never be what stands between the user and the app.
                if (path == WatchLink.STATUS_PATH) _ready.value = true
            }
        }
    }

    /**
     * Shows [DemoWalk] in place of the phone's data, until the next real update.
     * Debug builds only — the caller checks.
     */
    fun showDemo() {
        val status = DemoWalk.status(System.currentTimeMillis())
        _state.value = LinkState.Known(status)
        _map.value = DemoWalk.map(status.startedAtEpochMs)
        _claims.value = DemoWalk.claims
        _ready.value = true
    }

    fun detach() {
        dataClient.removeListener(this)
    }

    override fun onDataChanged(events: DataEventBuffer) {
        events.filter { it.type == DataEvent.TYPE_CHANGED }.forEach { accept(it.dataItem) }
    }

    /** Routes an item by its path. Read here, while the buffer it came in is alive. */
    private fun accept(item: DataItem) {
        when (item.uri.path) {
            WatchLink.STATUS_PATH -> _state.value = linkStateOf(item)
            WatchLink.MAP_PATH -> _map.value = WatchMap.fromFields(fieldsOf(item))
            WatchLink.CLAIMS_PATH -> _claims.value = WatchClaims.fromFields(fieldsOf(item))
        }
    }

    /**
     * Ask the phone to stop and claim. True when a phone was reached — not that
     * it stopped: the phone re-checks the walk itself and may decline, which
     * the next status shows.
     */
    suspend fun requestStop(): Boolean {
        val nodes = runCatching { Wearable.getNodeClient(context).connectedNodes.await() }
            .getOrDefault(emptyList())
        val messages = Wearable.getMessageClient(context)
        return nodes.map { node ->
            runCatching { messages.sendMessage(node.id, WatchLink.STOP_PATH, ByteArray(0)).await() }
                .isSuccess
        }.any { it }
    }

    /**
     * Open Enclose on the phone at [uri] — [WatchLink.START_URI] or
     * [WatchLink.OPEN_URI]. [onResult] hears whether the request reached it.
     */
    fun openOnPhone(uri: String, onResult: (Boolean) -> Unit) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val future = RemoteActivityHelper(context).startRemoteActivity(intent)
        future.addListener(
            { onResult(runCatching { future.get() }.isSuccess) },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun linkStateOf(item: DataItem): LinkState =
        statusOf(item)?.let { LinkState.Known(it) } ?: LinkState.Unreadable
}

/** The [WatchStatus] in a status data item, or null if it can't be read. */
fun statusOf(item: DataItem): WatchStatus? = WatchStatus.fromFields(fieldsOf(item))

/** An item's fields as the `:watchlink` codecs read them. */
private fun fieldsOf(item: DataItem): (String) -> Any? {
    val map = DataMapItem.fromDataItem(item).dataMap
    return { key -> map.get<Any>(key) }
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
