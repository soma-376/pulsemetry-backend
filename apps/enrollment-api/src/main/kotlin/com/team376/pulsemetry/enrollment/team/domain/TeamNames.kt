package com.team376.pulsemetry.enrollment.team.domain

import java.text.Normalizer
import java.util.Locale

/** 보관한 팀도 이름 충돌 검사에 포함한다. 문자 폭·대소문자 차이는 새 이름이 아니다. */
object TeamNames {
    fun same(left: String, right: String): Boolean = normalize(left) == normalize(right)
    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
}
