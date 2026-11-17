package link.xtxp.app.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

private const val TAG = "XtxpDrawing"

/**
 * The document rendered as SVG, by the system WebView, which draws
 * everything a browser would (the logo's CSS variables included). Touch an
 * element to select it; drag it to move it, which the app commits as a
 * `translate` on its `transform` when the finger lifts.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Drawing(
    svg: String,
    selected: String?,
    onSelect: (key: String) -> Unit,
    onMoved: (key: String, dx: Double, dy: Double) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val select by rememberUpdatedState(onSelect)
    val moved by rememberUpdatedState(onMoved)
    val error by rememberUpdatedState(onError)
    var ready by remember { mutableStateOf(false) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                val main = Handler(Looper.getMainLooper())
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun ready() {
                            main.post { ready = true }
                        }

                        @JavascriptInterface
                        fun select(key: String) {
                            main.post { select(key) }
                        }

                        @JavascriptInterface
                        fun moved(key: String, dx: Double, dy: Double) {
                            main.post { moved(key, dx, dy) }
                        }

                        @JavascriptInterface
                        fun error(message: String) {
                            main.post { error(message) }
                        }
                    },
                    "Xtxp",
                )
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        Log.i(TAG, "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                        return true
                    }
                }
                // The first drawing goes into the page itself; later ones
                // through show(), so a drag never reloads the page.
                val page = context.assets.open("drawing.html").bufferedReader().use { it.readText() }
                    .replace("<div id=\"c\"></div>", "<div id=\"c\">$svg</div>")
                loadDataWithBaseURL("https://xtxp.invalid/", page, "text/html", "utf-8", null)
            }
        },
        update = { view ->
            if (ready) {
                view.evaluateJavascript(
                    "show(${JSONObject.quote(svg)}, ${JSONObject.quote(selected ?: "")})",
                    null,
                )
            }
        },
    )
}
