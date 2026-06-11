package uz.kiosk.browser

/** A command received from the HTTP control endpoint. */
data class RemoteCommand(val name: String, val url: String? = null)

/**
 * Tiny bridge between the [KioskHttpService] (background) and the running
 * [MainActivity]. The activity registers a handler for WebView-related commands;
 * the service invokes it on the UI thread.
 */
object KioskBus {
    @Volatile
    var uiHandler: ((RemoteCommand) -> Unit)? = null

    fun send(cmd: RemoteCommand) {
        uiHandler?.invoke(cmd)
    }
}
