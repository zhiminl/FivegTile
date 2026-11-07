package moe.xzr.fivegtile

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.ServiceManager
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import java.lang.reflect.Method

/**
 * TelephonyManager is not properly initialized in this context, thus
 * everything is done by directly calling telephony service.
 * 
 * Uses pure reflection to avoid AIDL interface signature mismatches on Android 17+.
 */
class FivegControllerService : RootService() {
    companion object {
        private const val TAG = "FivegControllerService"
        
        private val telephonyService by lazy {
            ServiceManager.getService(Context.TELEPHONY_SERVICE)
        }
        
        private val iTelephony by lazy {
            // 使用反射获取 ITelephony 接口，避免 AIDL 签名不匹配
            val iTelephonyClass = Class.forName("com.android.internal.telephony.ITelephony")
            val asInterfaceMethod = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
                .getMethod("asInterface", IBinder::class.java)
            asInterfaceMethod.invoke(null, telephonyService)
        }
        
        private val reasonUser by lazy {
            Class.forName("android.telephony.TelephonyManager")
                .getDeclaredField("ALLOWED_NETWORK_TYPES_REASON_USER")
                .getInt(null)
        }
        
        private val typeNr by lazy {
            Class.forName("android.telephony.TelephonyManager")
                .getDeclaredField("NETWORK_TYPE_BITMASK_NR")
                .getLong(null)
        }

        @delegate:SuppressLint("PrivateApi", "BlockedPrivateApi")
        private val modeLte by lazy {
            Class.forName("com.android.internal.telephony.RILConstants")
                .getDeclaredField("NETWORK_MODE_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA")
                .getInt(null)
        }

        @delegate:SuppressLint("PrivateApi", "BlockedPrivateApi")
        private val modeNr by lazy {
            Class.forName("com.android.internal.telephony.RILConstants")
                .getDeclaredField("NETWORK_MODE_NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA")
                .getInt(null)
        }
        
        // 缓存反射方法
        private var getAllowedNetworkTypesMethod: Method? = null
        private var setAllowedNetworkTypesMethod: Method? = null
        private var getPreferredNetworkTypeMethod: Method? = null
        private var setPreferredNetworkTypeMethod: Method? = null
        
        private fun initReflectionMethods() {
            if (getAllowedNetworkTypesMethod != null) return
            
            try {
                val iTelephonyClass = iTelephony.javaClass
                Log.d(TAG, "iTelephony class: ${iTelephonyClass.name}")
                
                // 列出所有方法用于调试
                val methods = iTelephonyClass.methods
                Log.d(TAG, "Available methods on iTelephony:")
                methods.forEach { method ->
                    Log.d(TAG, "  - ${method.name}(${method.parameterTypes.joinToString { it.simpleName }}): ${method.returnType.simpleName}")
                }
                
                // 查找 getAllowedNetworkTypesForReason 方法
                getAllowedNetworkTypesMethod = methods.find { 
                    it.name == "getAllowedNetworkTypesForReason" && it.parameterTypes.size == 2
                }
                Log.d(TAG, "getAllowedNetworkTypesForReason method: ${getAllowedNetworkTypesMethod?.name}")
                
                // 查找 setAllowedNetworkTypesForReason 方法
                // Android 17+ 签名变为 (int, int, long, String): boolean
                // 旧版本签名是 (int, int, long): void/boolean
                setAllowedNetworkTypesMethod = methods.find { 
                    it.name == "setAllowedNetworkTypesForReason" && 
                    it.parameterTypes.size >= 3 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[2] == Long::class.javaPrimitiveType
                }
                Log.d(TAG, "setAllowedNetworkTypesForReason method: ${setAllowedNetworkTypesMethod?.name}, params: ${setAllowedNetworkTypesMethod?.parameterTypes?.size}, return type: ${setAllowedNetworkTypesMethod?.returnType?.simpleName}")
                
                // 查找 getPreferredNetworkType 方法
                getPreferredNetworkTypeMethod = methods.find { 
                    it.name == "getPreferredNetworkType" && it.parameterTypes.size == 1
                }
                Log.d(TAG, "getPreferredNetworkType method: ${getPreferredNetworkTypeMethod?.name}")
                
                // 查找 setPreferredNetworkType 方法
                setPreferredNetworkTypeMethod = methods.find { 
                    it.name == "setPreferredNetworkType" && it.parameterTypes.size == 2
                }
                Log.d(TAG, "setPreferredNetworkType method: ${setPreferredNetworkTypeMethod?.name}")
                
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to init reflection methods", e)
            }
        }
    }

    override fun onBind(intent: Intent) = object : IFivegController.Stub() {
        override fun compatibilityCheck(subId: Int): Boolean {
            return try {
                Log.d(TAG, "=== compatibilityCheck called ===")
                Log.d(TAG, "subId: $subId, SDK_INT: ${Build.VERSION.SDK_INT}")
                
                initReflectionMethods()
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Log.d(TAG, "Android S+ detected, using new API")
                    Log.d(TAG, "reasonUser: $reasonUser, typeNr: $typeNr")
                    
                    val getMethod = getAllowedNetworkTypesMethod
                    val setMethod = setAllowedNetworkTypesMethod
                    
                    Log.d(TAG, "getMethod: ${getMethod?.name}, setMethod: ${setMethod?.name}")
                    
                    if (getMethod != null && setMethod != null) {
                        Log.d(TAG, "Found both get and set methods")
                        val currentTypes = getMethod.invoke(iTelephony, subId, reasonUser) as Long
                        Log.d(TAG, "Current network types: $currentTypes")
                        
                        // 调用 set 方法（Android 17+ 需要 4 个参数，第 4 个是 callingPackage）
                        try {
                            val result = if (setMethod.parameterTypes.size >= 4) {
                                setMethod.invoke(iTelephony, subId, reasonUser, currentTypes, "moe.xzr.fivegtile")
                            } else {
                                setMethod.invoke(iTelephony, subId, reasonUser, currentTypes)
                            }
                            Log.d(TAG, "Set method returned: $result")
                        } catch (e: Throwable) {
                            Log.e(TAG, "Failed to call set method", e)
                        }
                        
                        true
                    } else {
                        Log.e(TAG, "Required methods not found: get=$getMethod, set=$setMethod")
                        false
                    }
                } else {
                    Log.d(TAG, "Android Q/R detected, using legacy API")
                    modeLte
                    modeNr
                    
                    val getMethod = getPreferredNetworkTypeMethod
                    val setMethod = setPreferredNetworkTypeMethod
                    
                    Log.d(TAG, "getMethod: ${getMethod?.name}, setMethod: ${setMethod?.name}")
                    
                    if (getMethod != null && setMethod != null) {
                        val currentType = getMethod.invoke(iTelephony, subId) as Int
                        setMethod.invoke(iTelephony, subId, currentType)
                        true
                    } else {
                        Log.e(TAG, "Legacy methods not found")
                        false
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Compatibility check failed", e)
                false
            }
        }

        override fun getFivegEnabled(subId: Int): Boolean {
            return try {
                initReflectionMethods()
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val getMethod = getAllowedNetworkTypesMethod
                    if (getMethod != null) {
                        val types = getMethod.invoke(iTelephony, subId, reasonUser) as Long
                        types and typeNr != 0L
                    } else {
                        Log.e(TAG, "getAllowedNetworkTypesForReason method not found")
                        false
                    }
                } else {
                    val getMethod = getPreferredNetworkTypeMethod
                    if (getMethod != null) {
                        val currentType = getMethod.invoke(iTelephony, subId) as Int
                        currentType == modeNr
                    } else {
                        Log.e(TAG, "getPreferredNetworkType method not found")
                        false
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "getFivegEnabled failed", e)
                false
            }
        }

        override fun setFivegEnabled(subId: Int, enabled: Boolean) {
            try {
                initReflectionMethods()
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val getMethod = getAllowedNetworkTypesMethod
                    val setMethod = setAllowedNetworkTypesMethod
                    
                    if (getMethod != null && setMethod != null) {
                        var curTypes = getMethod.invoke(iTelephony, subId, reasonUser) as Long
                        curTypes = if (enabled) {
                            curTypes or typeNr
                        } else {
                            curTypes and typeNr.inv()
                        }
                        
                        // 调用 set 方法（Android 17+ 需要 4 个参数，第 4 个是 callingPackage）
                        try {
                            val result = if (setMethod.parameterTypes.size >= 4) {
                                setMethod.invoke(iTelephony, subId, reasonUser, curTypes, "moe.xzr.fivegtile")
                            } else {
                                setMethod.invoke(iTelephony, subId, reasonUser, curTypes)
                            }
                            Log.d(TAG, "5G ${if (enabled) "enabled" else "disabled"} successfully, result: $result")
                        } catch (e: Throwable) {
                            Log.e(TAG, "Failed to set 5G state", e)
                        }
                    } else {
                        Log.e(TAG, "Required methods not found for setFivegEnabled")
                    }
                } else {
                    val setMethod = setPreferredNetworkTypeMethod
                    if (setMethod != null) {
                        val targetMode = if (enabled) modeNr else modeLte
                        setMethod.invoke(iTelephony, subId, targetMode)
                        Log.d(TAG, "Legacy 5G ${if (enabled) "enabled" else "disabled"} successfully")
                    } else {
                        Log.e(TAG, "setPreferredNetworkType method not found")
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "setFivegEnabled failed", e)
            }
        }
    }
}