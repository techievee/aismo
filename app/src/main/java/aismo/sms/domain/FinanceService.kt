package aismo.sms.domain

import aismo.sms.data.db.AismoDatabase
import aismo.sms.data.db.TransactionEntity
import aismo.sms.data.settings.SettingsRepository
import aismo.sms.domain.parser.TxnKind
import aismo.sms.telephony.NotificationCenter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class MonthSummary(
    val currency: String,
    val spentMinor: Long,
    val receivedMinor: Long,
    val byBucket: List<Pair<String, Long>>,
)

/** Spending summaries and monthly budget alerts built from parsed transactions. */
class FinanceService(
    private val db: AismoDatabase,
    private val settings: SettingsRepository,
    private val notifications: NotificationCenter,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    fun bucketOf(t: TransactionEntity): String = t.spendCategory ?: t.merchant ?: "Other"

    fun monthRange(month: LocalDate): Pair<Long, Long> {
        val start = month.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = month.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    fun summarise(transactions: List<TransactionEntity>): List<MonthSummary> =
        transactions.groupBy { it.currency }.map { (cur, list) ->
            val debits = list.filter { it.kind == TxnKind.DEBIT.name }
            MonthSummary(
                currency = cur,
                spentMinor = debits.sumOf { it.amountMinor },
                receivedMinor = list.filter { it.kind == TxnKind.CREDIT.name }.sumOf { it.amountMinor },
                byBucket = debits.groupBy(::bucketOf).map { (b, l) -> b to l.sumOf { it.amountMinor } }.sortedByDescending { it.second },
            )
        }.sortedByDescending { it.spentMinor }

    /** Called after a debit is stored: alerts once per month when a budget crosses its threshold. */
    suspend fun checkBudgets(txn: TransactionEntity) {
        if (txn.kind != TxnKind.DEBIT.name || !settings.current().budgetAlerts) return
        val month = Instant.ofEpochMilli(txn.occurredAt).atZone(zone).toLocalDate()
        val (from, to) = monthRange(month)
        val monthDebits = db.insights().transactionsBetweenNow(from, to).filter { it.kind == TxnKind.DEBIT.name }
        val monthKey = month.year * 100 + month.monthValue
        for (b in db.config().budgets()) {
            if (b.lastAlertMonth == monthKey) continue
            val spent = monthDebits
                .filter { it.currency == b.currency && (b.bucket == "*" || bucketOf(it).equals(b.bucket, ignoreCase = true)) }
                .sumOf { it.amountMinor }
            if (b.limitMinor > 0 && spent * 100 >= b.limitMinor * b.alertPercent) {
                notifications.showBudget(b.bucket, spent, b.limitMinor, b.currency)
                db.config().setBudgetAlertMonth(b.bucket, monthKey)
            }
        }
    }
}
