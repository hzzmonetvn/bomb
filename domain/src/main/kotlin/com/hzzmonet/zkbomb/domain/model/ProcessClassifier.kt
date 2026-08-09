package com.hzzmonet.zkbomb.domain.model

/**
 * Process classifier for categorizing runtime processes per master plan §9.
 */
object ProcessClassifier {

    private const val PER_USER_RANGE = 100_000
    private const val ISOLATED_START = 99_000
    private const val ISOLATED_END = 99_999

    fun classify(
        processName: String,
        packageNames: List<String>,
        uid: Int,
    ): ProcessCategory {
        if (processName.isBlank()) return ProcessCategory.UNKNOWN

        val appId = uid % PER_USER_RANGE
        if (appId in ISOLATED_START..ISOLATED_END || processName.contains("isolated")) {
            return ProcessCategory.ISOLATED
        }

        if (isWebViewProcess(processName)) {
            return ProcessCategory.WEBVIEW
        }

        if (packageNames.isNotEmpty()) {
            if (packageNames.any { it == processName }) {
                return ProcessCategory.MAIN
            }
            if (packageNames.any { processName.startsWith("$it:") }) {
                return ProcessCategory.APP_COMPONENT
            }
        }

        if (processName.contains(":")) {
            return ProcessCategory.APP_COMPONENT
        }

        if (appId >= 10000 && !looksLikeJavaPackage(processName)) {
            return ProcessCategory.NATIVE_CHILD
        }

        if (packageNames.isNotEmpty() && looksLikeJavaPackage(processName)) {
            return ProcessCategory.MAIN
        }

        return ProcessCategory.UNKNOWN
    }

    private fun isWebViewProcess(processName: String): Boolean {
        return processName.contains(":sandboxed_process") ||
            processName.contains(":webview_service") ||
            processName.contains("org.chromium") ||
            processName.contains("com.google.android.webview") ||
            processName.contains(":privileged_process") ||
            processName.contains(":renderer")
    }

    private fun looksLikeJavaPackage(name: String): Boolean {
        if (!name.contains(".")) return false
        val parts = name.split(".")
        return parts.size >= 2 && parts.all { segment ->
            segment.isNotEmpty() && segment.all { c -> c.isLetterOrDigit() || c == '_' }
        }
    }
}
