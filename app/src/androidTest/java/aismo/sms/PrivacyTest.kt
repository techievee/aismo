package aismo.sms

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import aismo.sms.data.ai.EmbeddingModel
import aismo.sms.domain.guide.Guide

/**
 * The app promises that nothing leaves the phone. ONNX Runtime brings the INTERNET permission and a telemetry client
 * that starts at launch; the manifest removes both. These checks ask the OS about the installed app, so a dependency
 * update that reintroduces either fails here.
 */
@RunWith(AndroidJUnit4::class)
class PrivacyTest {
    private val app get() = ApplicationProvider.getApplicationContext<AismoApp>()
    private val packages get() = app.packageManager

    @Test fun theAppDoesNotAskForNetworkAccess() {
        val requested = packages.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()
        assertFalse("INTERNET must not be requested: $requested", Manifest.permission.INTERNET in requested)
        assertEquals("the OS denies the app network access", PackageManager.PERMISSION_DENIED, packages.checkPermission(Manifest.permission.INTERNET, app.packageName))
    }

    @Test fun thePrivacyPageExplainsExactlyThePermissionsTheInstalledAppHolds() {
        val held = packages.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
            .filterNot { it.startsWith(app.packageName) }.toSet()   // the app's own signature permission for receivers is not one it asks the user for
        val explained = Guide.permissions.map { it.permission }.toSet()
        assertEquals("the Privacy page must list exactly what the app holds", held, explained)
    }

    @Test fun noTelemetryComponentIsRegistered() {
        val info = packages.getPackageInfo(app.packageName, PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES)
        val names = (info.providers.orEmpty().map { it.name } + info.receivers.orEmpty().map { it.name } + info.services.orEmpty().map { it.name })
        assertTrue("no component may be a telemetry client: $names", names.none { it.contains("telemetry", ignoreCase = true) })
        assertNull(packages.resolveContentProvider("${app.packageName}.onnxruntime_telemetry_initializer", 0))
    }

    @Test fun searchByMeaningStillRunsWithoutTheNetworkCapability() = kotlinx.coroutines.runBlocking {
        val vector = app.container.embedder.embed("verification code")
        assertEquals(EmbeddingModel.DIMENSION, vector.size)
        Unit
    }
}
