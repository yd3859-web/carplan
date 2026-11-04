package com.htb.carplan.shared.text

/**
 * 조사 고르기 (디자인 평가 2026-11-04: "중2동를(을)" 같은 기계 표기 대신). The last letter decides: a Hangul syllable
 * by its final consonant, a digit by how it is read (3 = 삼 → 받침), a Latin letter by a rough rule. "로" also
 * counts ㄹ as no 받침 (서울로).
 */
object Josa {
    private fun batchim(word: String, forRo: Boolean = false): Boolean {
        val c = word.trimEnd().lastOrNull { !it.isWhitespace() && it !in ")]}'\"" } ?: return false
        return when (c) {
            in '가'..'힣' -> {
                val fin = (c - '가') % 28
                if (forRo) fin != 0 && fin != 8 else fin != 0
            }
            in '0'..'9' -> if (forRo) c in "036" else c in "013678"
            else -> c.lowercaseChar() in (if (forRo) "mnkpt" else "lmnkpt")
        }
    }

    /** word + 을/를. */
    fun eul(word: String) = word + if (batchim(word)) "을" else "를"
    /** word + 이/가. */
    fun ga(word: String) = word + if (batchim(word)) "이" else "가"
    /** word + 은/는. */
    fun eun(word: String) = word + if (batchim(word)) "은" else "는"
    /** word + 과/와. */
    fun gwa(word: String) = word + if (batchim(word)) "과" else "와"
    /** word + 으로/로. */
    fun ro(word: String) = word + if (batchim(word, forRo = true)) "으로" else "로"
}
