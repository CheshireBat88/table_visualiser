package com.groupfund.app.ui.detail

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.groupfund.app.data.sheets.PaymentData
import com.groupfund.app.data.sheets.PaymentHistoryEntry
import com.groupfund.app.data.sheets.PaymentHistoryKindFilter
import com.groupfund.app.data.sheets.PaymentHistoryPeriod
import com.groupfund.app.data.sheets.PaymentKind
import com.groupfund.app.data.sheets.money
import com.groupfund.app.data.sheets.paymentHistory
import com.groupfund.app.data.sheets.paymentHistoryTotals
import java.time.format.DateTimeFormatter

private val HISTORY_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")

private fun kindLabel(kind: PaymentKind): String = when (kind) {
    PaymentKind.MONTHLY -> "Месячный взнос"
    PaymentKind.COLLECTION -> "Сбор"
    PaymentKind.COLLECTION_REFUND -> "Излишек сбора"
    PaymentKind.COLLECTION_CANCELLED -> "Отменённый сбор"
    PaymentKind.COLLECTION_TRANSFER -> "Перенос из сбора"
    PaymentKind.REFUND -> "Возврат"
}

private fun historyDescription(entry: PaymentHistoryEntry): String =
    entry.payment.comment.ifBlank { kindLabel(entry.kind) }

/** История всех платежей участника с фильтрами по виду и периоду. */
@Composable
internal fun MemberPaymentHistoryDialog(
    payments: List<PaymentData>,
    memberName: String,
    currency: String,
    onDismiss: () -> Unit,
) {
    var kindFilter by remember { mutableStateOf(PaymentHistoryKindFilter.ALL) }
    var period by remember { mutableStateOf(PaymentHistoryPeriod.ALL) }

    val entries = remember(payments, memberName, kindFilter, period) {
        paymentHistory(payments, memberName, kindFilter, period)
    }
    val totals = remember(entries) { paymentHistoryTotals(entries) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("История платежей: $memberName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    PaymentHistoryKindFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = kindFilter == filter,
                            onClick = { kindFilter = filter },
                            label = {
                                Text(
                                    when (filter) {
                                        PaymentHistoryKindFilter.ALL -> "Все"
                                        PaymentHistoryKindFilter.MONTHLY -> "Взносы"
                                        PaymentHistoryKindFilter.COLLECTIONS -> "Сборы"
                                        PaymentHistoryKindFilter.REFUNDS -> "Возвраты"
                                    },
                                )
                            },
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    PaymentHistoryPeriod.entries.forEach { option ->
                        FilterChip(
                            selected = period == option,
                            onClick = { period = option },
                            label = {
                                Text(
                                    when (option) {
                                        PaymentHistoryPeriod.ALL -> "Всё время"
                                        PaymentHistoryPeriod.LAST_30_DAYS -> "30 дней"
                                        PaymentHistoryPeriod.LAST_90_DAYS -> "90 дней"
                                        PaymentHistoryPeriod.CURRENT_YEAR -> "Этот год"
                                    },
                                )
                            },
                        )
                    }
                }
                Text(
                    "Найдено операций: ${entries.size}",
                    style = MaterialTheme.typography.labelSmall,
                )
                if (entries.isEmpty()) {
                    Text(
                        "За выбранный период платежей нет.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        itemsIndexed(entries) { index, entry ->
                            if (index > 0) HorizontalDivider()
                            PaymentHistoryRow(entry)
                        }
                    }
                }
                HorizontalDivider()
                Text(
                    "Поступления: ${money(totals.income)} $currency",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Возвраты: ${money(totals.refunded)} $currency",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Итого по строкам: ${money(totals.net)} $currency",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Взносы в отдельные сборы видны здесь, но в общий баланс группы не входят.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}

@Composable
private fun PaymentHistoryRow(entry: PaymentHistoryEntry) {
    val refund = entry.kind == PaymentKind.REFUND
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                historyDescription(entry),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "${entry.date?.format(HISTORY_DATE) ?: entry.payment.date.ifBlank { "дата не указана" }}" +
                    " · ${kindLabel(entry.kind)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
        }
        Text(
            (if (refund) "−" else "+") + money(entry.payment.amount),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (refund) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}
