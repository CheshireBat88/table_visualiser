package com.groupfund.app.ui.create

/**
 * Данные для клона группы: название, базовый взнос и состав участников исходной группы.
 * Финансы (платежи/расходы/сборы) в копию не переносятся.
 */
data class ClonePrefill(
    val title: String,
    val baseAmount: Int,
    val members: List<String>,
    val memberBirthdays: Map<String, String>,
)

/**
 * Разовый «пакет клона»: заполняется прямо перед переходом в мастер создания,
 * забирается (и очищается) при открытии мастера. Такой способ надёжнее передачи
 * данных через URL-аргументы навигации — не зависит от кодирования запросов.
 */
object CloneHolder {
    var prefill: ClonePrefill? = null

    fun take(): ClonePrefill? = prefill.also { prefill = null }
}