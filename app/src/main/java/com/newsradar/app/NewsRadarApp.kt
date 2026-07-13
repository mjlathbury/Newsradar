package com.newsradar.app

import android.app.Application
import android.graphics.Bitmap
import coil.ImageLoader
import com.newsradar.app.util.Notifier
import com.newsradar.app.work.FetchScheduler
import okhttp3.Interceptor
import okhttp3.OkHttpClient

class NewsRadarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        Notifier.ensureChannel(this)
        FetchScheduler.schedule(this, hour = 7)

        // Image loader tuned for our news outlets (Coil 2.7.0):
        //  - Browser User-Agent so signed CDN URLs (Guardian i.guim.co.uk ?s=)
        //    validate instead of returning HTTP 401.
        //  - Accept header without image/avif so CDNs downgrade to WebP/JPEG
        //    (older devices lack a native AVIF decoder -> silent Coil failure).
        //  - Referer set for hosts that gate images on it.
        //  - allowHardware(false): force SOFTWARE bitmaps. Hardware (GPU) bitmaps
        //    have a max texture size; 13MB Metro JPEGs / large Guardian images can
        //    exceed it and crash or hang the render thread -> ANR. Software bitmaps
        //    avoid the texture limit (at a small perf cost, fine for news thumbs).
        //  - RGB_565 halves memory vs ARGB_8888, killing the OOM pressure that
        //    froze the UI while scrolling image-heavy feeds.
        val ua = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        val headerInterceptor = Interceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", ua)
                .header("Accept", "image/webp,image/apng,image/*,*/*;q=0.8")
                .header("Referer", "https://www.google.com/")
                .build()
            chain.proceed(req)
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(headerInterceptor)
            .build()

        val loader = ImageLoader.Builder(this)
            .okHttpClient(client)
            .allowHardware(false)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .build()

        coil.Coil.setImageLoader(loader)
    }
}
