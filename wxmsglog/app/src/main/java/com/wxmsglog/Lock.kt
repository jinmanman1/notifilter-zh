package com.wxmsglog

import android.app.KeyguardManager
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * 应用锁。用系统生物识别（指纹/人脸）+ 锁屏密码兜底。
 *
 * 三个要点：
 * 1. 不是自己存密码——没有密码可存，就没有密码可被绕。验证交给系统，
 *    本应用只拿「成功 / 失败」这一个结果。
 * 2. unlocked 是进程级标记：验证过一次，本次使用期间不再重复弹。
 *    从最近任务切回来不会又要求指纹，但进程被杀后重开会重新验证。
 * 3. 设备没设锁屏/没录指纹时不允许开启，避免把自己锁在门外。
 */
object Lock {

    /** 本次进程内是否已通过验证。 */
    @Volatile
    var unlocked: Boolean = false

    /** 防止重复弹窗（比如 Activity 快速重建）。 */
    private var prompting = false

    private val ALLOWED: Int =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** 这台设备现在能不能用来验证。 */
    fun canUse(context: Context): Boolean {
        val code = runCatching {
            BiometricManager.from(context).canAuthenticate(ALLOWED)
        }.getOrDefault(-99)
        if (code == BiometricManager.BIOMETRIC_SUCCESS) return true
        // 低版本对「设备凭据」的探测可能返回 UNKNOWN，用 KeyguardManager 再兜一次
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isDeviceSecure == true
    }

    /**
     * 弹验证框。
     * @param onSuccess 验证通过
     * @param onCancel 取消 / 失败 / 锁定，调用方一般直接 finish()
     * @return 是否真的弹出了验证框。false 表示设备没有可用的验证方式或已有弹窗在等，
     *         调用方据此决定要不要放行，避免把用户卡在空白页。
     */
    fun prompt(activity: FragmentActivity, onSuccess: () -> Unit, onCancel: () -> Unit): Boolean {
        if (!canUse(activity)) return false
        if (prompting) return false
        prompting = true

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                prompting = false
                unlocked = true
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                prompting = false
                onCancel()
            }

            // 按错手指：系统会自己提示重试，这里什么都不用做
            override fun onAuthenticationFailed() = Unit
        }

        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.lock_title))
            .setSubtitle(activity.getString(R.string.lock_sub))
            .setAllowedAuthenticators(ALLOWED)
            .build()
        prompt.authenticate(info)
        return true
    }
}
