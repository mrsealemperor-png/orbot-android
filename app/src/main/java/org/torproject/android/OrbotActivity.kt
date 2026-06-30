package org.torproject.android

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.findNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.scottyab.rootbeer.RootBeer
import com.seal.tyulenvpn.R
import org.torproject.android.service.OrbotConstants
import org.torproject.android.ui.connect.ConnectViewModel
import org.torproject.android.ui.connect.RequestPostNotificationPermission
import org.torproject.android.ui.core.BaseActivity
import org.torproject.android.ui.core.DeviceAuthenticationPrompt
import org.torproject.android.ui.kindness.SnowflakeProxyService
import org.torproject.android.util.Prefs
import org.torproject.android.util.sendIntentToService
import org.torproject.android.util.showToast
import org.torproject.jni.TorService

class OrbotActivity : BaseActivity() {

    private lateinit var navController: NavController
    private lateinit var bottomNavigationView: BottomNavigationView

    private var lastNavMenuIndex = -1

    var portSocks: Int = -1
    var portHttp: Int = -1

    // used to hide UI while password isn't obtained
    private var rootLayout: View? = null

    private val connectViewModel: ConnectViewModel by viewModels()

    private var rootDetectionShown = false

    private val orbotServiceBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                OrbotConstants.LOCAL_ACTION_STATUS -> {
                    val status = intent.getStringExtra(OrbotConstants.EXTRA_STATUS)
                    connectViewModel.updateStatus(status)
                }
                OrbotConstants.LOCAL_ACTION_LOG -> {
                    val log = intent.getStringExtra(OrbotConstants.EXTRA_LOG)
                    connectViewModel.updateLog(log)
                }
                OrbotConstants.LOCAL_ACTION_PORTS) -> {
                    portSocks = intent.getIntExtra(OrbotConstants.EXTRA_SOCKS_PROXY_PORT, -1)
                    portHttp = intent.getIntExtra(OrbotConstants.EXTRA_HTTP_PROXY_PORT, -1)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }

        // programmatically set title to "Orbot" since camo mode will overwrite it here from manifest
        title = getString(R.string.app_name)

        try {
            createOrbot()
        } catch (_: RuntimeException) {
            //catch this to avoid malicious launches as document Cure53 Audit: ORB-01-009 WP1/2: Orbot DoS via exported activity (High)
            //clear malicious intent
            intent = null
            finish()
        }
    }

    private fun createOrbot() {
        setContentView(R.layout.activity_orbot)
        rootLayout = findViewById(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.nav_fragment)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        navController = findNavController(R.id.nav_fragment)
        bottomNavigationView = findViewById(R.id.bottom_navigation)
        bottomNavigationView.setupWithNavController(navController)

        val bottomNavigationContainer = findViewById<View>(R.id.bottomNavContainer)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id == R.id.connectFragment || destination.id == R.id.moreFragment || destination.id == R.id.kindnessFragment) {
                bottomNavigationContainer.visibility = View.VISIBLE
            } else {
                bottomNavigationContainer.visibility = View.GONE
            }
        }

        val navOptionsLeftToRight = NavOptions.Builder()
            .setEnterAnim(R.anim.slide_in_right)
            .setExitAnim(R.anim.slide_out_left)
            .setPopEnterAnim(R.anim.slide_in_right)
            .setPopExitAnim(R.anim.slide_out_left)
            .build()

        val navOptionsRightToLeft = NavOptions.Builder()
            .setEnterAnim(R.anim.slide_in_left)
            .setExitAnim(R.anim.slide_out_right)
            .setPopEnterAnim(R.anim.slide_in_left)
            .setPopExitAnim(R.anim.slide_out_right)
            .build()

        bottomNavigationView.setOnItemSelectedListener { item ->
            val navOptions = if ((navController.currentDestination?.id ?: 0) < item.itemId) {
                navOptionsLeftToRight
            } else {
                navOptionsRightToLeft
            }

            if (lastNavMenuIndex != item.itemId) {
                when (item.itemId) {
                    R.id.connectFragment ->
                        navController.navigate(R.id.connectFragment, null, navOptions)

                    R.id.kindnessFragment ->
                        navController.navigate(R.id.kindnessFragment, null, navOptions)

                    R.id.moreFragment ->
                        navController.navigate(R.id.moreFragment, null, navOptions)
                }
            }
            lastNavMenuIndex = item.itemId
            true
        }

        val filter = IntentFilter().apply {
            addAction(OrbotConstants.LOCAL_ACTION_STATUS)
            addAction(OrbotConstants.LOCAL_ACTION_LOG)
            addAction(OrbotConstants.LOCAL_ACTION_PORTS)
        }

        ContextCompat.registerReceiver(
            this, orbotServiceBroadcastReceiver, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        requestNotificationPermission()

        Prefs.initWeeklyWorker(this)

        if (!rootDetectionShown && Prefs.detectRoot() && RootBeer(this).isRooted) {
            applicationContext.showToast(getString(R.string.root_warning))
            rootDetectionShown = true
        }

        onBackPressedDispatcher.addCallback(this) {
            navController.currentBackStackEntry?.let {
                when (it.destination.id) {
                    R.id.connectFragment -> finish()
                    R.id.kindnessFragment, R.id.moreFragment -> {
                        bottomNavigationView.selectedItemId = R.id.connectFragment
                    }
                    else -> navController.popBackStack()
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean = navController.navigateUp()

    private fun requestNotificationPermission() {
        // automatically granted on Android 12 and lower
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
            return
        val checkPostNotificationPerm =
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        when (checkPostNotificationPerm) {
            PackageManager.PERMISSION_GRANTED -> {
                Log.d(TAG, "Granted ${Manifest.permission.POST_NOTIFICATIONS}")
            }
            else -> {
                Log.d(TAG, "Prompting For ${Manifest.permission.POST_NOTIFICATIONS}")
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d(TAG, "User just granted ${Manifest.permission.POST_NOTIFICATIONS}")
        } else {
            Log.d(TAG, "Notification denied")
            RequestPostNotificationPermission().show(
                supportFragmentManager, RequestPostNotificationPermission.TAG
            )
        }
    }

    override fun onStart() {
        super.onStart()
        promptDeviceAuthenticationIfRequired()
    }

    private fun promptDeviceAuthenticationIfRequired() {
        if (Prefs.requireDeviceVpnAuth()) {
            DeviceAuthenticationPrompt().show(
                this,
                getString(R.string.app_name),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        rootLayout?.visibility = View.VISIBLE
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        finish()
                    }
                }
            )
        } else {
            rootLayout?.visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        sendIntentToService(OrbotConstants.CMD_ACTIVE)

        if (Prefs.beSnowflakeProxy()) {
            SnowflakeProxyService.startSnowflakeProxyForegroundService(this)
        }

        lastNavMenuIndex = bottomNavigationView.selectedItemId
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(orbotServiceBroadcastReceiver)
        } catch (_: IllegalArgumentException) {}
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_VPN) {
            if (resultCode == RESULT_OK) {
                sendIntentToService(OrbotConstants.CMD_START)
            } else {
                Toast.makeText(this, "VPN status registration failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val TAG = "OrbotActivity"
        private const val REQUEST_CODE_VPN = 1001
    }
}
