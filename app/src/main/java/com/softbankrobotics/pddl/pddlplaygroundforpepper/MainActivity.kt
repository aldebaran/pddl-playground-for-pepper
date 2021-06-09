package com.softbankrobotics.pddl.pddlplaygroundforpepper

import android.accounts.NetworkErrorException
import android.os.Bundle
import android.os.RemoteException
import android.preference.PreferenceManager
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import com.aldebaran.qi.Session
import com.aldebaran.qi.sdk.*
import com.aldebaran.qi.sdk.design.activity.RobotActivity
import com.aldebaran.qi.sdk.design.activity.conversationstatus.SpeechBarDisplayStrategy
import com.fasterxml.jackson.databind.ObjectMapper
import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.*
import com.softbankrobotics.pddlsandbox.WotanApplication.Companion.PLANNING_HISTORIES_METADATA_PREFERENCE_KEY
import com.softbankrobotics.pddlsandbox.WotanApplication.Companion.jacksonObjectMapper
import com.softbankrobotics.pddlsandbox.WotanApplication.Companion.managementController
import com.softbankrobotics.pddlsandbox.WotanApplication.Companion.privateSession
import com.softbankrobotics.pddlsandbox.common.SettingsException
import com.softbankrobotics.pddlsandbox.planning.listStores
import com.softbankrobotics.pddlsandbox.server.ReportInfo
import com.softbankrobotics.pddlsandbox.utils.*
import kotlinx.android.synthetic.main.overlay_feedback_button.view.*
import kotlinx.android.synthetic.main.retry_countdown.view.*
import kotlinx.android.synthetic.main.view_loading.view.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.File
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import android.content.Intent as AndroidIntent


class MainActivity : RobotActivity(), RobotLifecycleCallbacks, CoroutineScope {

    /**
     * Supervisor job to allow its children to fail independently of each other
     */
    private val job = SupervisorJob()

    /**
     * Scope the coroutine to the activity lifecycle
     */
    override val coroutineContext: CoroutineContext = Dispatchers.IO + job

    /**
     * Subscriptions to the world state and plan change
     */
    private val subscriptions = Disposables()

    /**
     * Signal to all the screen touch events
     */
    private val emittableTouched = Signal<Unit>()

    /**
     * Whether activity is running (between onResume and onPause).
     */
    private val activityRunning = AtomicBoolean(false)

    /**
     * Whether we registered to Qi SDK's callbacks.
     */
    private val sdkRegistered = AtomicBoolean(false)

    /**
     * A job for scheduling a call.
     */
    private var scheduledCall: Deferred<Any>? = null


    /**
     * Class to control the action flow
     */
    private var controller: Controller? = null

    /**
     * Lock to ensure createController() and resetController() are not called concurrently
     */
    private var controllerLock = Mutex()

    /**
     * FrameLayout for registering onTouch listener
     */
    private lateinit var frameLayout: FrameLayout

    /**
     * A view to show in loading phases.
     * */
    private lateinit var loadingView: View

    /**
     * A job for setting up and starting the controller.
     */
    private var ensuringController: Deferred<Any>? = null

    /**
     * A job that waits for a given time with no focus, before trying to stimulate it.
     */
    private var stimulatingFocusIfNotReceived: Deferred<Unit>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSpeechBarDisplayStrategy(SpeechBarDisplayStrategy.IMMERSIVE)
        setContentView(R.layout.main)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            emittableTouched.emit(Unit)
        }
        return super.dispatchTouchEvent(event)
    }

    private fun resetViewToLoading() {
        frameLayout = findViewById(R.id.frame_layout)
        loadingView = View.inflate(this, R.layout.view_loading, null)
        loadingView.loading_progress_text.text = getString(R.string.loading)
        loadingView.loading_progress_bar.progress = 0
        switchView(loadingView, "loadingView", frameLayout)
    }

    override fun onResume() {
        super.onResume()
        resetViewToLoading()
        loadingView.loading_progress_text.text = getString(R.string.connecting)
    }

    override fun onPause() {
        super.onPause()
        launch {
            resetController()
        }
        runBlocking {
            scheduledCall?.cancelAndJoin()
        }
    }

    override fun onDestroy() {
        QiSDK.unregister(this, this)
        job.cancel()
        super.onDestroy()
    }

    /**
     * Destroy the current controller and reset the view to the loading screen
     */
    private suspend fun resetController() = controllerLock.withLock {
        ensuringController?.cancelAndJoin()
        controller?.let { controller ->
            controller.stop()
            postOnUiThread {
                resetViewToLoading()
                loadingView.loading_progress_text.text = getString(R.string.connecting)
            }
        }
        controller = null
        subscriptions.dispose()
    }

    /**
     * Creates a new controller (if absent) and setup the debug view
     */
    private suspend fun ensureController(session: Session, qiContext: QiContext): Controller =
        controllerLock.withLock {
            val currentController = controller
            if (currentController != null)
                return currentController

            val newController = Controller(qiContext)
            this.controller = newController
            return newController
        }

    private fun reportErrorAndScheduleCall(error: String, function: () -> Unit) {
        postOnUiThread {
            val retryCountdown = createView(R.layout.retry_countdown, this)
            retryCountdown.error_title.text = error
            retryCountdown.retry_countdown.text = ""
            switchView(retryCountdown, "error retry countdown", frameLayout)

            scheduledCall = async {
                var timeLeft = 12_000L // in ms
                val delta = 100L // in ms
                while (timeLeft > 0) {
                    postOnUiThread {
                        retryCountdown.retry_countdown.text =
                            getString(R.string.retry_in_x_s, timeLeft.toDouble() / 1000.0)
                    }
                    delay(delta)
                    timeLeft -= delta
                }
                onUiThread {
                    resetViewToLoading()
                }
                function()
            }
        }
    }

    /**
     * Creates a report in the given directory
     * 1- Creates the following files:
     *  - $dir/wotan_logs.json
     *  - $dir/wotan_history.tsv
     *  - $dir/naoqi_logs.json
     * 2- Creates a human perception on the robot head
     * Returns the report preparation response
     */
    suspend fun createReportIn(destination: File?): ReportInfo {
        val (wotanLogs, wotanHistory, naoqiLogs) = destination?.let { dir ->
            fun <T> tryWriteJSONToExternal(
                destination: File,
                reportLabel: String,
                jacksonObjectMapper: ObjectMapper,
                collectData: () -> T
            ): String? {
                return try {
                    jacksonObjectMapper.writeValue(destination, collectData())
                    destination.path
                } catch (e: Throwable) {
                    Timber.e(e, "Error when collecting $reportLabel")
                    null
                }
            }

            val wotanLogs = tryWriteJSONToExternal(
                File(dir, "wotan_logs.json"),
                "app logs",
                jacksonObjectMapper
            ) {
                WotanApplication.timberTree.collectLogs()
            }

            val wotanHistory = try {
                runBlocking { controller?.savePlanningHistory() }
                val planningHistoryStores = listStores(
                    jacksonObjectMapper,
                    PreferenceManager.getDefaultSharedPreferences(this),
                    PLANNING_HISTORIES_METADATA_PREFERENCE_KEY
                )
                val historyDestination = File(dir, "wotan_history.tsv")
                val output = historyDestination.outputStream().writer()
                planningHistoryStores.forEach { store ->
                    output.write("Time (ns) since ${store.timestamp} (ms)\tStateAndPlan\n")
                    val input = File(store.path).inputStream().reader()
                    input.forEachLine {
                        output.write(it)
                        output.write("\n")
                    }
                }
                output.flush()
                historyDestination.path
            } catch (e: Throwable) {
                Timber.e(e, "Error when collecting planning histories")
                null
            }

            val naoqiLogs = tryWriteJSONToExternal(
                File(dir, "naoqi_logs.json"),
                "NAOqi logs",
                jacksonObjectMapper
            ) {
                managementController.collectNAOqiLogs()
            }
            Triple(wotanLogs, wotanHistory, naoqiLogs)
        } ?: Triple(null, null, null)

        val dumpPath = dumpPerceptionBlackBox(privateSession.get())

        return ReportInfo(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            githash = BuildConfig.GIT_HASH,
            humanPerceptionBlackBoxPath = dumpPath,
            wotanLogsFileName = wotanLogs,
            wotanHistoryFileName = wotanHistory,
            naoqiLogsFileName = naoqiLogs
        )
    }

    override fun onRobotFocusGained(qiContext: QiContext) {
        Timber.d("Got focus")
        stimulatingFocusIfNotReceived?.cancel()
        stimulatingFocusIfNotReceived = null

        val session = privateSession.get()
        if (session != null) {
            ensuringController = async {
                try {
                    ensureController(session, qiContext).apply {
                        Timber.d("Setting up controller")
                        setUp()
                        Timber.d("Starting controller")
                        start()
                    }
                } catch (e: SettingsException) {
                    Timber.w(e, "Settings issue prevented initialization")
                    onUiThread {
                        Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
                        startActivity(
                            AndroidIntent(this@MainActivity, MainSettingsActivity::class.java)
                        )
                    }
                } catch (e: NetworkErrorException) {
                    Timber.w(e, "Network issue prevented initialization")
                    reportErrorAndScheduleCall(getString(R.string.network_error)) {
                        onRobotFocusGained(qiContext)
                    }
                } catch (e: RemoteException) {
                    Timber.w(e, "Remote service issue prevented initialization")
                    reportErrorAndScheduleCall(getString(R.string.remote_error)) {
                        onRobotFocusGained(qiContext)
                    }
                } catch (e: CancellationException) {
                    Timber.w(e, "Chat and action loading was cancelled during initialization")
                } catch (t: Throwable) {
                    Timber.e(t, "Error at initialization")
                    postOnUiThread {
                        throw t // Controller is a critical component, ensure the app crashes if absent
                    }
                }
            }
        }
    }

    override fun onRobotFocusLost() {
        Timber.d("Focus lost.")
        launch {
            resetController()
            stimulatingFocusIfNotReceived?.cancelAndJoin()
            stimulatingFocusIfNotReceived = null
        }
    }

    override fun onRobotFocusRefused(reason: String?) {
        stimulatingFocusIfNotReceived?.cancel()
        stimulatingFocusIfNotReceived = null
        reportErrorAndScheduleCall(getString(R.string.focus_refused)) { triggerRebirth() }
    }
}
