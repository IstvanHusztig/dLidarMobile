package com.dlidar.mobile.domain.model

/** The scanner's rules for a join request, checked before sending (it answers bad_request otherwise). */
object WifiCredentials {

    enum class Problem { SsidLength, PasswordMissing, PasswordFormat, PasswordForOpenNetwork, UnsupportedSecurity }

    /** Spec: an SSID is 1 to 32 bytes of UTF-8. */
    fun ssidProblem(ssid: String): Problem? =
        if (ssid.toByteArray(Charsets.UTF_8).size in 1..32) null else Problem.SsidLength

    /** Spec: 8 to 63 printable ASCII characters, or 64 hexadecimal digits; none for open networks. */
    fun passwordProblem(security: WifiSecurity, password: CharArray?): Problem? = when {
        security is WifiSecurity.Unsupported -> Problem.UnsupportedSecurity
        !security.needsPassword -> if (password == null || password.isEmpty()) null else Problem.PasswordForOpenNetwork
        password == null || password.isEmpty() -> Problem.PasswordMissing
        password.size in 8..63 && password.all { it in ' '..'~' } -> null
        password.size == 64 && password.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } -> null
        else -> Problem.PasswordFormat
    }

    fun problem(ssid: String, security: WifiSecurity, password: CharArray?): Problem? =
        ssidProblem(ssid) ?: passwordProblem(security, password)
}
