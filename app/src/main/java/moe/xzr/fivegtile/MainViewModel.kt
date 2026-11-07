package moe.xzr.fivegtile

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.AndroidViewModel
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "MainViewModel"
    }

    enum class CompatibilityState {
        PENDING,
        ROOT_DENIED,
        NOT_COMPATIBLE,
        COMPATIBLE,
    }

    @Composable
    fun getCompatibilityState(): CompatibilityState {
        val ret by produceState(initialValue = CompatibilityState.PENDING) {
            Log.d(TAG, "Starting compatibility check")
            
            val hasRoot = withContext(Dispatchers.IO) {
                try {
                    val granted = Utils.isRootGranted()
                    Log.d(TAG, "Root check result: $granted")
                    granted
                } catch (e: Exception) {
                    Log.e(TAG, "Root check failed", e)
                    false
                }
            }
            
            if (!hasRoot) {
                Log.w(TAG, "Root not granted")
                value = CompatibilityState.ROOT_DENIED
            } else {
                Log.d(TAG, "Binding to RootService")
                
                // Launch timeout in the coroutine scope
                val timeoutJob = launch {
                    delay(10000) // 10 second timeout
                    if (value == CompatibilityState.PENDING) {
                        Log.e(TAG, "RootService bind timeout - service not responding")
                        value = CompatibilityState.NOT_COMPATIBLE
                    }
                }
                
                try {
                    RootService.bind(
                        Intent(getApplication(), FivegControllerService::class.java),
                        object : ServiceConnection {
                            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                                Log.d(TAG, "RootService connected: $name")
                                timeoutJob.cancel()
                                
                                try {
                                    val fivegController = IFivegController.Stub.asInterface(service)
                                    val subId = SubscriptionManager.getDefaultDataSubscriptionId()
                                    Log.d(TAG, "Default data subId: $subId")
                                    
                                    val compatible = fivegController.compatibilityCheck(subId)
                                    Log.d(TAG, "Compatibility check result: $compatible")
                                    
                                    value = if (compatible)
                                        CompatibilityState.COMPATIBLE
                                    else
                                        CompatibilityState.NOT_COMPATIBLE
                                } catch (e: Exception) {
                                    Log.e(TAG, "Compatibility check failed", e)
                                    value = CompatibilityState.NOT_COMPATIBLE
                                }
                            }

                            override fun onServiceDisconnected(name: ComponentName?) {
                                Log.w(TAG, "RootService disconnected: $name")
                                timeoutJob.cancel()
                                if (value == CompatibilityState.PENDING) {
                                    value = CompatibilityState.NOT_COMPATIBLE
                                }
                            }
                        })
                } catch (e: Exception) {
                    Log.e(TAG, "RootService.bind failed", e)
                    timeoutJob.cancel()
                    value = CompatibilityState.NOT_COMPATIBLE
                }
            }
        }
        return ret
    }
}