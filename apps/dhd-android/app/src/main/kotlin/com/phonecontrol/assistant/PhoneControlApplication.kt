package com.phonecontrol.assistant

import android.app.Application
import android.view.Surface
import com.phonecontrol.assistant.apps.AppPermissionRepository
import com.phonecontrol.assistant.bridge.DevBridgeServer
import com.phonecontrol.assistant.data.ConversationStore
import com.phonecontrol.assistant.developer.DhdAdbController
import com.phonecontrol.assistant.developer.DhdAdbProcessRunner
import com.phonecontrol.assistant.developer.DhdTaskDisplayBackend
import com.phonecontrol.assistant.developer.DhdVirtualDisplayManager
import com.phonecontrol.assistant.policy.PolicyEngine
import com.phonecontrol.assistant.session.SessionCoordinator
import com.phonecontrol.assistant.execution.PhoneObservationProvider
import com.phonecontrol.assistant.execution.TypedPhoneActionTransport
import com.phonecontrol.assistant.execution.TaskDisplaySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PhoneControlApplication : Application() {
    private val previewScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var appPermissionRepository: AppPermissionRepository
        private set
    lateinit var developerModeController: DhdAdbController
        private set
    lateinit var processRunner: DhdAdbProcessRunner
        private set
    lateinit var observationProvider: PhoneObservationProvider
        private set
    lateinit var taskDisplayBackend: DhdTaskDisplayBackend
        private set
    lateinit var sessionCoordinator: SessionCoordinator
        private set
    lateinit var conversationStore: ConversationStore
        private set
    lateinit var devBridgeServer: DevBridgeServer
        private set

    fun attachTaskPreview(session: TaskDisplaySession, surface: Surface) {
        previewScope.launch {
            // The backend publishes attachment errors to its preview state.
            runCatching { taskDisplayBackend.attachLiveSurface(session, surface) }
        }
    }

    fun detachTaskPreview(session: TaskDisplaySession, surface: Surface) {
        // Application ownership lets cleanup finish after Activity destruction.
        previewScope.launch { taskDisplayBackend.detachLiveSurface(session, surface) }
    }

    override fun onCreate() {
        super.onCreate()
        appPermissionRepository = AppPermissionRepository(this)
        developerModeController = DhdAdbController(this).also { it.start() }
        processRunner = DhdAdbProcessRunner(developerModeController)
        taskDisplayBackend = DhdTaskDisplayBackend(
            this,
            DhdVirtualDisplayManager(this, developerModeController),
            processRunner,
        )
        observationProvider = PhoneObservationProvider(this, processRunner, taskDisplayBackend)
        conversationStore = ConversationStore(this)
        sessionCoordinator = SessionCoordinator(
            enabledPackagesProvider = { appPermissionRepository.enabledPackages() },
            // Structural observation checks are always enabled; guard-region
            // fingerprints add the optional stricter visual check per action.
            policyEngine = PolicyEngine(enforceObservationFreshness = true),
            transport = TypedPhoneActionTransport(
                context = this,
                observationProvider = observationProvider,
                processRunner = processRunner,
                executionReadyProvider = { developerModeController.status.value.privilegedApiReady },
                executionUnavailableMessageProvider = { developerModeController.status.value.message },
                enforceObservationFreshness = true,
                taskDisplayBackend = taskDisplayBackend,
            ),
            conversationStore = conversationStore,
            phoneActionsReadyProvider = { developerModeController.status.value.privilegedApiReady },
            fullAccessProvider = { appPermissionRepository.isFullAccessEnabled() },
            taskDisplayRequiredProvider = { true },
        )
        // The bridge accepts paired LAN connections for the development
        // companion. adb forwarding remains compatible because forwarded
        // clients arrive as loopback and bypass the LAN token check.
        devBridgeServer = DevBridgeServer(
            context = this,
            coordinator = sessionCoordinator,
            observationProvider = observationProvider,
            allowedPackagesProvider = { appPermissionRepository.enabledPackages() },
            fullAccessProvider = { appPermissionRepository.isFullAccessEnabled() },
            taskDisplayRequiredProvider = { true },
        ).also { it.start() }
    }
}
