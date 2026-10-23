package net.coursehunter.app

import android.content.Context
import com.lynx.tasm.provider.AbsTemplateProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.Executors

/** Loads `.lynx.bundle` files from the debug dev server (http) or from APK assets. */
class BundleProvider(context: Context) : AbsTemplateProvider() {
    private val appContext = context.applicationContext

    override fun loadTemplate(uri: String, callback: Callback) {
        executor.execute {
            try {
                if (BuildConfig.DEBUG && (uri.startsWith("http://") || uri.startsWith("https://"))) {
                    http.newCall(Request.Builder().url(uri).build()).execute().use {
                        if (!it.isSuccessful) throw IOException("HTTP ${it.code} for $uri")
                        callback.onSuccess(it.body!!.bytes())
                    }
                } else {
                    appContext.assets.open(uri).use { callback.onSuccess(it.readBytes()) }
                }
            } catch (e: IOException) {
                callback.onFailed(e.message)
            }
        }
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
        private val http by lazy { OkHttpClient() }
    }
}
