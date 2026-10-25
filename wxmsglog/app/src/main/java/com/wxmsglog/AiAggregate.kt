package com.wxmsglog

import android.content.Context

/**
 * AI 聚合：把上次扫描之后的新消息过一遍，能抽出待办的直接落库。
 *
 * 三个关键设计：
 *
 * 1. **水位线去重**。记一个「上次扫到哪个时间点」，下次只处理比它新的。
 *    这比给每条消息打标记简单，也不会因为消息被清理而错乱。
 *
 * 2. **已生成待办的跳过**。就算水位线因为某种原因回退，靠 todo.src 也能
 *    认出「这条已经变成待办了」，不会重复弹。
 *
 * 3. **一次只跑一批**。模型每条要一两秒，一次四十条已经是大半分钟。
 *    剩下的留给下次——水位线会往前推，不会漏。
 *
 * 准确率优先：宁可一条一跑慢一点，也不批量喂——小模型同时看多条会漏项，
 * 而漏掉的待办就是这个功能没做到。速度靠 native 层的 KV 前缀复用解决。
 */
object AiAggregate {

    data class Result(
        val added: Int,
        val scanned: Int,
        val error: String?,
    )

    /** 单次最多分析多少条。再多手机等不起，用户也会以为死机了。 */
    private const val MAX_PER_RUN = 40

    /**
     * 第一次跑（水位线为 0）时往回看多久。
     * 不能直接扫全库——有人存了几万条，第一次就跑几小时。
     */
    private const val FIRST_RUN_WINDOW = 24L * 3600 * 1000

    /**
     * 跑一轮聚合。阻塞调用，必须在工作线程。
     *
     * @param now 当前时间，测试时便于固定。
     * @param onProgress 处理进度回调，参数是「已完成 / 总数」。
     *        每五条报一次——再密就是无谓的通知刷新，再疏就看不出在动。
     */
    fun run(
        context: Context,
        now: Long = System.currentTimeMillis(),
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): Result {
        val model = ModelStore.current(context)
            ?: return Result(0, 0, "还没装模型，先去设置里下一个")

        val db = MessageDb.get(context)

        var since = Prefs.aiLastScan(context)
        if (since <= 0L) since = now - FIRST_RUN_WINDOW

        // desc = false 取最早的一批，配合水位线往前推，保证不漏不重
        val batch = try {
            db.queryFiltered(from = since + 1, limit = MAX_PER_RUN, desc = false)
        } catch (t: Throwable) {
            return Result(0, 0, "读消息失败")
        }
        if (batch.isEmpty()) return Result(0, 0, null)

        val alreadyTodo = try {
            db.todoSourceIds()
        } catch (t: Throwable) {
            emptySet<Long>()
        }
        val existingText = try {
            db.todos().map { it.text }.toHashSet()
        } catch (t: Throwable) {
            HashSet<String>()
        }

        // 先筛出真正值得问模型的：已经生成过待办的、太短没语境的、
        // 纯数字和「好的收到」这类，都不必占用推理。
        val targets = batch.filter {
            it.id !in alreadyTodo && LlmExtract.worthAsking(it.body)
        }

        // 这批里一条都不值得问：水位线照样要往前推，否则下次还在原地打转。
        // 顺便也就不用加载模型了——省几秒和 1.5G 内存。
        if (targets.isEmpty()) {
            Prefs.setAiLastScan(context, batch.maxOf { it.ts })
            return Result(0, batch.size, null)
        }

        // 模型加载要几秒，放在确认有事可做之后
        val err = Llm.load(model)
        if (err != null) return Result(0, 0, err)

        var added = 0

        // 先报一次总数，让用户马上知道这一轮要看多少条
        if (onProgress != null) runCatching { onProgress(0, targets.size) }

        // 一条一跑。批量（一次五条、输出带编号）能快三成，但小模型同时盯五件事
        // 会漏项——编号对账挡得住错位，挡不住跳过。漏一条就是这个功能没做到。
        // 速度交给 native 层的 KV 前缀复用，那部分一点准确率都不损失。
        for ((idx, m) in targets.withIndex()) {
            if (onProgress != null && (idx + 1) % 5 == 0) {
                runCatching { onProgress(idx + 1, targets.size) }
            }

            val hit = try {
                LlmExtract.extract(m.body, m.sender, m.ts, now)
            } catch (t: Throwable) {
                // 单条失败不影响后面的
                null
            } ?: continue

            if (hit.text.isBlank()) continue
            if (hit.text in existingText) continue

            val id = try {
                db.addTodo(hit.text, hit.due, m.id)
            } catch (t: Throwable) {
                -1L
            }
            if (id > 0L) {
                added++
                existingText.add(hit.text)
                // 有明确时间且还没过的，顺手把闹钟排上
                if (hit.due > now) {
                    runCatching { Alarm.schedule(context, id, hit.due) }
                }
            }
        }

        // 水位线推到这批里最新的那条。用 max 而不是最后一条：查询是按 ts 升序，
        // 但同一毫秒可能有多条，取 max 最稳。
        Prefs.setAiLastScan(context, batch.maxOf { it.ts })
        return Result(added, batch.size, null)
    }

    /** 还有多少条没被扫过。界面用来显示「还有 N 条待分析」。 */
    fun backlog(context: Context, now: Long = System.currentTimeMillis()): Int {
        var since = Prefs.aiLastScan(context)
        if (since <= 0L) since = now - FIRST_RUN_WINDOW
        return try {
            MessageDb.get(context).countFiltered("", null, null, since + 1, Long.MAX_VALUE)
        } catch (t: Throwable) {
            0
        }
    }
}
