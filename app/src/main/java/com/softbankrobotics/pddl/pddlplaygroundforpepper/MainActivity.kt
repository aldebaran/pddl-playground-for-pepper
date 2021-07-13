package com.softbankrobotics.pddl.pddlplaygroundforpepper

import android.os.Bundle
import android.os.RemoteException
import android.view.MotionEvent
import android.widget.FrameLayout
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.QiSDK
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks
import com.aldebaran.qi.sdk.design.activity.RobotActivity
import com.aldebaran.qi.sdk.design.activity.conversationstatus.SpeechBarDisplayStrategy
import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.*
import com.softbankrobotics.pddl.pddlplaygroundforpepper.databinding.RetryCountdownBinding
import com.softbankrobotics.pddl.pddlplaygroundforpepper.databinding.ViewLoadingBinding
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.engageableHumansAreGreeted
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.engageableHumansAreHappy
import com.softbankrobotics.pddlplanning.and
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import android.content.Intent as AndroidIntent


class MainActivity : RobotActivity(), RobotLifecycleCallbacks {

    private val scope = createAsyncCoroutineScope()

    /**
     * Subscriptions to the world state and plan change
     */
    private val subscriptions = Disposables()

    /**
     * Signal to all the screen touch events
     */
    private val screenTouched = Signal<Unit>()

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
    private lateinit var loadingView: ViewLoadingBinding

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
        Timber.plant(Timber.DebugTree())
        QiSDK.register(this, this)
    }

    /**
     * Forwards screen touch events to the controller, via signal emission.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            screenTouched.emit(Unit)
        }
        return super.dispatchTouchEvent(event)
    }

    private fun resetViewToLoading() {
        frameLayout = findViewById(R.id.frame_layout)
        loadingView = ViewLoadingBinding.inflate(layoutInflater)
        loadingView.loadingProgressText.text = getString(R.string.loading)
        loadingView.loadingProgressBar.progress = 0
        switchView(loadingView.root, "loadingView", frameLayout)
    }

    override fun onResume() {
        super.onResume()
        resetViewToLoading()
        loadingView.loadingProgressText.text = getString(R.string.connecting)
    }

    override fun onPause() {
        super.onPause()
        scope.launch {
            resetController()
        }
        runBlocking {
            scheduledCall?.cancelAndJoin()
        }
    }

    override fun onDestroy() {
        QiSDK.unregister(this, this)
        scope.cancel()
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
                loadingView.loadingProgressText.text = getString(R.string.connecting)
            }
        }
        controller = null
        subscriptions.dispose()
    }

    /**
     * Creates a new controller (if absent) and setup the debug view
     */
    private suspend fun ensureController(qiContext: QiContext): Controller =
        controllerLock.withLock {
            val currentController = controller
            if (currentController != null)
                return currentController

            val newController =
                Controller.createDefaultController(this, frameLayout, screenTouched, qiContext)
            this.controller = newController
            return newController
        }

    private fun reportErrorAndScheduleCall(error: String, function: () -> Unit) {
        postOnUiThread {
            val retryCountdown = RetryCountdownBinding.inflate(layoutInflater)
            retryCountdown.errorTitle.text = error
            retryCountdown.retryCountdown.text = ""
            switchView(retryCountdown.root, "error retry countdown", frameLayout)

            scheduledCall = scope.async {
                var timeLeft = 12_000L // in ms
                val delta = 100L // in ms
                while (timeLeft > 0) {
                    postOnUiThread {
                        retryCountdown.retryCountdown.text =
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

    override fun onRobotFocusGained(qiContext: QiContext) {
        Timber.d("Got focus")
        stimulatingFocusIfNotReceived?.cancel()
        stimulatingFocusIfNotReceived = null

        ensuringController = scope.async {
            try {
                Timber.d("Setting up controller")
                ensureController(qiContext).apply {
                    setGoal(
                        and(
                            engageableHumansAreGreeted.goal,
                            engageableHumansAreHappy.goal
                        )
                    )
                    Timber.d("Starting controller")
                    start()
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

    override fun onRobotFocusLost() {
        Timber.d("Focus lost.")
        scope.launch {
            resetController()
            stimulatingFocusIfNotReceived?.cancelAndJoin()
            stimulatingFocusIfNotReceived = null
        }
    }

    override fun onRobotFocusRefused(reason: String?) {
        stimulatingFocusIfNotReceived?.cancel()
        stimulatingFocusIfNotReceived = null

        // The Qi SDK might sometimes fail to take the focus at startup,
        // or if the robot state is disabled.
        // In that case, just restart the activity indefinitely,
        // because there is no event-based API to track the state change.
        reportErrorAndScheduleCall(getString(R.string.focus_refused)) {
            startActivity(AndroidIntent.makeRestartActivityTask(this.intent?.component))
        }
    }
}
