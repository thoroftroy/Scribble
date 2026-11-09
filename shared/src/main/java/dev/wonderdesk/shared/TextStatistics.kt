package dev.wonderdesk.shared

data class TextStatistics(val characters: Int, val words: Int, val lines: Int)

fun textStatistics(text: String): TextStatistics {
    var words = 0
    var inWord = false
    var lines = if (text.isEmpty()) 0 else 1
    text.forEach { character ->
        if (character == '\n') lines++
        if (character.isWhitespace()) inWord = false
        else if (!inWord) { words++; inWord = true }
    }
    return TextStatistics(text.codePointCount(0, text.length), words, lines)
}

/** Count overlapping matches without allocating a substring for every position. */
fun countTextMatches(text: String, query: String): Int {
    if (query.isEmpty()) return 0
    var count = 0
    var start = 0
    while (start <= text.length - query.length) {
        val index = text.indexOf(query, startIndex = start)
        if (index < 0) break
        count++
        start = index + 1
    }
    return count
}
