# R8 configuration for the Bomb application.
#
# Deliberately short. Almost every keep rule an app "needs" is already shipped as
# a consumer rule by the library that needs it — AndroidX and Compose both do
# this — and adding keeps that duplicate them only makes the shrinker weaker
# without making the app more correct. Anything added here must name the concrete
# thing that breaks without it.

# ---- Entry points -----------------------------------------------------------
#
# Components declared in AndroidManifest.xml are kept automatically by AGP, so
# MainActivity needs no rule. Rules appear here only for entry points the
# manifest cannot express.

# ---- Compose ----------------------------------------------------------------
#
# androidx.compose.* ships its own consumer rules. The one thing they do not
# cover is `@Composable` functions reached only through reflection, and Bomb has
# none — every composable is called from Kotlin. No rules needed.

# ---- Kotlin -----------------------------------------------------------------
#
# Bomb uses no kotlin-reflect and no kotlinx-serialization, so `kotlin.Metadata`
# is not needed at runtime and R8 may discard it.

# ---- Diagnostics ------------------------------------------------------------
#
# Keep source file names and line numbers in stack traces. Without this a crash
# report from a ROM tester is a list of `a.a.a(Unknown Source)` and is worth
# nothing. The cost is a few KB in the mapping-backed attributes; the mapping
# file in build/outputs/mapping/release/ is what deobfuscates them.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
