package com.hzzmonet.zkbomb.domain.model

/**
 * Classification of Android runtime processes as specified in master plan §9.
 */
enum class ProcessCategory {
    /** Primary process of an application (e.g. com.example.app). */
    MAIN,

    /** Secondary process created for app components (e.g. com.example.app:push). */
    APP_COMPONENT,

    /** Isolated sandboxed process (e.g. UID in isolated range or isolated process). */
    ISOLATED,

    /** Chromium / WebView render or sandboxed process. */
    WEBVIEW,

    /** Native child daemon/process spawned without standard Android component lifecycle. */
    NATIVE_CHILD,

    /** Unknown or unclassifiable process. */
    UNKNOWN,
}
