package net.ithandsfree.softphone.notify

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.ithandsfree.softphone.data.AccountStore
import net.ithandsfree.softphone.data.ApiException
import net.ithandsfree.softphone.data.SoftphoneApi
import net.ithandsfree.softphone.data.ThreadInfo
import net.ithandsfree.softphone.data.normalizeDidDigits

/**
 * Poll BFF threads for every enrolled SMS line. Returns aggregate unread plus
 * per-line thread snapshots for in-app badges and system notifications.
 */
object SmsInboxPoller {
    data class LineInbox(
        val accountId: String,
        val label: String,
        val threads: List<ThreadInfo>,
        val unread: Int,
    )

    data class PollResult(
        val lines: List<LineInbox>,
        val totalUnread: Int,
    )

    suspend fun poll(context: Context, store: AccountStore = AccountStore(context)): PollResult =
        withContext(Dispatchers.IO) {
            val lines = mutableListOf<LineInbox>()
            var total = 0
            for (account in store.list()) {
                if (!account.capSms || account.did.isBlank()) continue
                val token = store.token(account.id) ?: continue
                val api = SoftphoneApi.forAccount(account)
                val threads = try {
                    api.threads(token, normalizeDidDigits(account.did)).threads
                } catch (e: ApiException) {
                    if (e.httpCode == 401) {
                        // Session expired — skip quietly; UI login paths refresh tokens.
                        continue
                    }
                    emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
                val unread = threads.sumOf { it.unread.coerceAtLeast(0) }
                total += unread
                lines += LineInbox(account.id, account.label, threads, unread)
                if (unread > 0) {
                    MessageNotifier.notifyNewThreads(
                        context = context.applicationContext,
                        lineLabel = account.label,
                        threads = threads,
                        resolveName = { it },
                    )
                }
            }
            PollResult(lines = lines, totalUnread = total)
        }
}
