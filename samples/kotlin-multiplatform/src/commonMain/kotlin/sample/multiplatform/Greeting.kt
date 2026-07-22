package sample.multiplatform

fun protectedGreeting(): String = "kotlin-multiplatform-protected-value"

fun protectedStringMatrix(key: String): List<String> {
    val selected =
        when (key) {
            "kmp-alpha-key" -> "kmp-switch-alpha"
            "kmp-beta-key" -> "kmp-switch-beta\twith-tab"
            else -> "kmp-switch-default"
        }
    val lambda = { "kmp-lambda-value" }
    return listOf(
        "kmp-array-value",
        "kmp-line-one\nkmp-line-two",
        "kmp-unicode-\u4F60\u597D-\uD83D\uDE80",
        selected,
        lambda(),
    )
}
