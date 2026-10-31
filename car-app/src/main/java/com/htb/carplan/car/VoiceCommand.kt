package com.htb.carplan.car

/**
 * ⑧ 음성 명령 (차 화면 원칙: 세세한 조작은 음성). What the driver said after pressing 🎤, turned into one action.
 * Word lists, not grammar: STT text is short and noisy, so the first family that matches wins, in this order.
 */
enum class VoiceCommand(val words: List<String>) {
    REVERT(listOf("원래대로", "원래경로", "되돌려", "취소")),
    SKIP(listOf("건너뛰", "건너 뛰", "다음칸", "빼줘", "패스")),
    FUEL(listOf("주유소", "충전소", "기름", "주유", "충전")),
    PARKING(listOf("주차장", "주차", "파킹")),
    FOOD(listOf("맛집", "식당", "밥집", "먹을")),
    WHERE(listOf("어디", "얼마나", "몇분", "언제도착", "남았", "다음은", "다음 목적지")),
    START(listOf("안내", "출발", "가자", "길안내")),
    ;

    companion object {
        /** Null when nothing matches (the car says "잘 못 들었어요" and shows the words it knows). */
        fun parse(text: String?): VoiceCommand? {
            val t = text?.lowercase()?.replace(" ", "") ?: return null
            if (t.isBlank()) return null
            return entries.firstOrNull { c -> c.words.any { t.contains(it.replace(" ", "")) } }
        }

        /** What to say when the words did not match. */
        const val HELP = "주유소, 주차장, 맛집, 다음 어디야, 건너뛰기, 원래대로 중에 말해 주세요."
    }
}
