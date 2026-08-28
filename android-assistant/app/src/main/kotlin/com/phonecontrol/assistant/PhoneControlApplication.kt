package com.phonecontrol.assistant

import android.app.Application
import com.phonecontrol.assistant.apps.AppPermissionRepository
import com.phonecontrol.assistant.bridge.DevBridgeServer
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
    lateinit var devBridgeServer: DevBridgeServer
        private set

    override fun onCreate() {
        super.onCreate()
        appPermissionRepository = AppPermissionRepository(this)
        shizukuController = ShizukuController().also { it.start() }
        shizukuProcessRunner = ShizukuProcessRunner(shizukuController)
        observationProvider = ShizukuObservationProvider(this, shizukuProcessRunner)
        sessionCoordinator = SessionCoordinator(
            enabledPackagesProvider = { appPermissionRepository.enabledPackages() },
            policyEngine = PolicyEngine(),
            transport = ShizukuActionTransport(
                controller = shizukuController,
                context = this,
                observationProvider = observationProvider,
                processRunner = shizukuProcessRunner,
            ),
        )
        // The bridge is localhost-only and intended for adb-forwarded
        // development sessions. It is deliberately not a production network
        // endpoint or an MCP server by itself.
        devBridgeServer = DevBridgeServer(
            coordinator = sessionCoordinator,
            observationProvider = observationProvider,
        ).also { it.start() }
    }
}
