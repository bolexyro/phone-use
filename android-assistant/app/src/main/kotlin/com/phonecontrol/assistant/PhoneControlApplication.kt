package com.phonecontrol.assistant

import android.app.Application
import com.phonecontrol.assistant.apps.AppPermissionRepository
import com.phonecontrol.assistant.policy.PolicyEngine
import com.phonecontrol.assistant.session.SessionCoordinator
import com.phonecontrol.assistant.shizuku.ShizukuActionTransport
import com.phonecontrol.assistant.shizuku.ShizukuController

class PhoneControlApplication : Application() {
    lateinit var appPermissionRepository: AppPermissionRepository
        private set
    lateinit var shizukuController: ShizukuController
        private set
    lateinit var sessionCoordinator: SessionCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        appPermissionRepository = AppPermissionRepository(this)
        shizukuController = ShizukuController().also { it.start() }
        sessionCoordinator = SessionCoordinator(
            enabledPackagesProvider = { appPermissionRepository.enabledPackages() },
            policyEngine = PolicyEngine(),
            transport = ShizukuActionTransport(shizukuController),
        )
    }
}
