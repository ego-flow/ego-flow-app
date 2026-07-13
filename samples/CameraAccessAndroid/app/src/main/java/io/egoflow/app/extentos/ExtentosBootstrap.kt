// EXTENTOS-GENERATED — do not edit manually. Regenerate via generateConnectionModule.
package io.egoflow.app.extentos

import android.app.Application
import android.util.Log
import io.egoflow.app.BuildConfig
import com.extentos.glasses.core.CapabilityKind
import com.extentos.glasses.core.ExtentosConfig
import com.extentos.glasses.core.ExtentosGlasses
import com.extentos.glasses.core.ExtentosResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application subclass that instantiates the Extentos SDK and opens the
 * transport on app start. Reference `(application as ExtentosBootstrap).glasses`
 * from your Activity / Composable / Handler classes to subscribe to SDK
 * primitives.
 *
 * Declared `open` so customer code can subclass this when it needs to run
 * additional init logic in Application.onCreate() — e.g. a connection-state
 * observer or DI wiring. Pattern:
 *
 *     class MyApp : ExtentosBootstrap() {
 *         override fun onCreate() {
 *             super.onCreate()         // wires `glasses` first
 *             // ⚠️ Do NOT start a mic/camera capture handler here. Subscribing to
 *             // glasses.audio.transcriptions() / recordDiscrete() / audioChunks()
 *             // (or starting the foreground service) needs the RECORD_AUDIO runtime
 *             // permission — NOT granted at Application start, so it throws
 *             // SecurityException and the app dies before the first Activity draws.
 *             // Start capture handlers from an Activity AFTER the permission-grant
 *             // callback (see getPermissions + getProductionChecklist).
 *         }
 *     }
 *
 * If you subclass, set `android:name` in AndroidManifest.xml to your
 * subclass (e.g. `.MyApp`) instead of `.extentos.ExtentosBootstrap`.
 * If you don't need extra init, leave the manifest pointing at this class.
 *
 * ── ⚠️ Field-init constraint ──────────────────────────────────────────
 * Top-level property initializers on Application subclasses run BEFORE
 * `attachBaseContext` wires the Context, so any field-init that touches
 * Context methods (`getFilesDir`, `getCacheDir`, `getSharedPreferences`,
 * etc., directly or via classes that take `this` in their constructor)
 * crashes the app at start with NullPointerException.
 *
 *     // ❌ DOES NOT WORK — NPE at app launch:
 *     class MyApp : ExtentosBootstrap() {
 *         val library = LibraryStore(this)         // LibraryStore.<init> → getFilesDir() → NPE
 *         val prefs = getSharedPreferences("…", 0) // same
 *     }
 *
 *     // ✓ Use `by lazy` (first read happens after super.onCreate()):
 *     class MyApp : ExtentosBootstrap() {
 *         val library: LibraryStore by lazy { LibraryStore(this) }
 *         override fun onCreate() {
 *             super.onCreate()
 *             library.someMethod() // ← first read; lazy initializer fires here, Context is live
 *         }
 *     }
 *
 *     // ✓ Or initialize inside onCreate(), after super.onCreate():
 *     class MyApp : ExtentosBootstrap() {
 *         lateinit var library: LibraryStore; private set
 *         override fun onCreate() {
 *             super.onCreate()
 *             library = LibraryStore(this)
 *         }
 *     }
 *
 * The `appScope` field below works because `CoroutineScope(...)` doesn't
 * touch `Context` at construction — keep that pattern (init objects that
 * don't reference `this` Context) for any peer field you add.
 */
open class ExtentosBootstrap : Application() {
    lateinit var glasses: ExtentosGlasses
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        glasses = ExtentosGlasses.create(
            ExtentosConfig(
                applicationContext = this,
                debug = BuildConfig.DEBUG,
                usedCapabilities = setOf(
                    CapabilityKind.Camera,
                ),
            )
        )

        appScope.launch {
            // connect() returns an ExtentosResult — pattern-match it. Do NOT wrap
            // in runCatching: lifecycle ops report failure via the Err variant
            // (not a thrown exception), and runCatching would also swallow the
            // CancellationException that cancels appScope (RDQ #29).
            when (val result = glasses.connection.connect()) {
                is ExtentosResult.Ok -> Unit
                is ExtentosResult.Err ->
                    Log.e(EXTENTOS_TAG, "glasses.connection.connect() failed: " + result.error)
            }
        }
    }

    private companion object {
        const val EXTENTOS_TAG = "Extentos"
    }
}
