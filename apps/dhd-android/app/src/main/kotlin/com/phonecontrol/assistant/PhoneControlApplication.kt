package com.phonecontrol.assistant

import android.app.Application
import com.phonecontrol.assistant.apps.AppPermissionRepository
import com.phonecontrol.assistant.bridge.DevBridgeServer
import com.phonecontrol.assistant.data.ConversationStore
import com.phonecontrol.assistant.policy.PolicyEngine
import com.phonecontrol.assistant.session.SessionCoordinator
import com.phonecontrol.assistant.shizuku.ShizukuActionTransport
import com.phonecontrol.assistant.shizuku.ShizukuController
import com.phonecontrol.assistant.shizuku.ShizukuObservationProvider
import com.phonecontrol.assistant.shizuku.ShizukuProcessRunner

class PhoneControlApplication : Application() {
    lateinit var appPermissionRepository: AppPermissionRepository
        private set
    lateinit var shizukuController: ShizukuController
        private set
    lateinit var shizukuProcessRunner: ShizukuProcessRunner
        private set
    lateinit var observationProvider: ShizukuObservationProvider
        private set
    lateinit var sessionCoordinator: SessionCoordinator
        private set
    lateinit var conversationStore: ConversationStore
        private set
    lateinit var devBridgeServer: DevBridgeServer
        private set

    override fun onCreate() {
        super.onCreate()
        appPermissionRepository = AppPermissionRepository(this)
        shizukuController = ShizukuController().also { it.start() }
        shizukuProcessRunner = ShizukuProcessRunner(shizukuController)
        observationProvider = ShizukuObservationProvider(this, shizukuProcessRunner)
        conversationStore = ConversationStore(this)
        sessionCoordinator = SessionCoordinator(
            enabledPackagesProvider = { appPermissionRepository.enabledPackages() },
            // Screenshot freshness/guard checks are deliberately deferred in
            // the first assistant prototype and can be re-enabled here later.
            policyEngine = PolicyEngine(enforceObservationFreshness = false),
            transport = ShizukuActionTransport(
                controller = shizukuController,
                context = this,
                observationProvider = observationProvider,
                processRunner = shizukuProcessRunner,
                enforceObservationFreshness = false,
            ),
            conversationStore = conversationStore,
        )
        // The bridge accepts paired LAN connections for the development
        // companion. adb forwarding remains compatible because forwarded
        // clients arrive as loopback and bypass the LAN token check.
        devBridgeServer = DevBridgeServer(
            context = this,
            coordinator = sessionCoordinator,
            observationProvider = observationProvider,
            allowedPackagesProvider = appPermissionRepository::enabledPackages,
        ).also { it.start() }
    }
}
