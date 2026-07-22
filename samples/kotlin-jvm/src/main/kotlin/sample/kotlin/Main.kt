package sample.kotlin

private const val KOTLIN_JVM_CONSTANT = "kotlin-jvm-static-final-value"

fun main() {
    val values =
        arrayOf(
            KOTLIN_JVM_CONSTANT,
            "kotlin-jvm-array-value",
            "kotlin-jvm-line-one\nkotlin-jvm-line-two",
            "kotlin-jvm-unicode-\u4F60\u597D-\uD83D\uDE80",
            "kotlin-jvm\u0000\uD800middle\uDC00\uD83D\uDE00suffix",
        )
    val selected =
        when ("kotlin-jvm-beta-key") {
            "kotlin-jvm-alpha-key" -> "kotlin-jvm-switch-alpha"
            "kotlin-jvm-beta-key" -> "kotlin-jvm-switch-beta\twith-tab"
            else -> "kotlin-jvm-switch-default"
        }
    val lambda = { "kotlin-jvm-lambda-value" }
    check(selected == "kotlin-jvm-switch-beta\twith-tab") { "kotlin-jvm-switch-verification-failed" }
    check(lambda() == "kotlin-jvm-lambda-value") { "kotlin-jvm-lambda-verification-failed" }
    println(values.joinToString("|") + "|" + selected + "|" + lambda())
}
