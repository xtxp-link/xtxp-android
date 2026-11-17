package link.xtxp.app.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

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
    modifier: Modifier = Modifier,
) {
    val select by rememberUpdatedState(onSelect)
    val moved by rememberUpdatedState(onMoved)
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
                        fun select(key: String) {
                            main.post { select(key) }
                        }

                        @JavascriptInterface
                        fun moved(key: String, dx: Double, dy: Double) {
                            main.post { moved(key, dx, dy) }
                        }
                    },
                    "Xtxp",
                )
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        ready = true
                    }
                }
                loadUrl("file:///android_asset/drawing.html")
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
