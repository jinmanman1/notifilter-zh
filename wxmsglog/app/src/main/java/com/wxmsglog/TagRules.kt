package com.wxmsglog

/**
 * 消息分类：靠规则先过一遍。
 *
 * 为什么不用模型分类：
 *
 * 1. 分类是「判别」任务，特征极其明显——「验证码」「取件码」「账单」这些词
 *    摆在那儿，正则的准确率比 1.7B 还高。
 * 2. 模型分一条要 0.3~0.5 秒，一万条就是两个小时。规则一万条不到一秒。
 *
 * 所以这一层全用规则，模型只在「AI 细分类」里作为可选项补充。
 * 这和待办提取是同一个思路：规则能确定的绝不动模型。
 */
object TagRules {

    const val NONE = ""
    const val CODE = "code"
    const val DELIVERY = "delivery"
    const val BILL = "bill"
    const val AD = "ad"
    const val WORK = "work"
    const val LIFE = "life"

    /** 全部标签，按优先级排列。 */
    val ALL = listOf(CODE, DELIVERY, BILL, AD, WORK, LIFE)

    private val CODE_WORDS = arrayOf("验证码", "校验码", "动态码", "验证吗", "授权码", "确认码")
    private val DELIVERY_WORDS = arrayOf(
        "取件", "快递", "驿站", "签收", "派送", "快递柜", "丰巢", "菜鸟",
        "顺丰", "中通", "圆通", "韵达", "申通", "京东物流", "极兔", "邮政",
    )
    private val BILL_WORDS = arrayOf(
        "账单", "扣款", "还款", "余额", "信用卡", "储蓄卡", "交易", "转账",
        "支付成功", "已支付", "消费", "欠费", "缴费", "充值", "退款", "到账",
    )
    private val AD_WORDS = arrayOf(
        "优惠", "立减", "折扣", "限时", "抢购", "满减", "领取", "券",
        "活动", "秒杀", "特价", "包邮", "促销", "新人", "退订",
    )
    private val WORK_WORDS = arrayOf(
        "会议", "项目", "方案", "汇报", "需求", "上线", "评审", "加班",
        "排期", "联调", "测试", "文档", "周报", "日报", "对接", "甲方",
        "客户", "合同", "报销", "审批", "流程", "工单",
    )

    /** 数字串，验证码的特征。 */
    private val DIGITS = Regex("\\d{4,8}")

    /**
     * 判一个标签。判不出来返回 [NONE]，让它在「未分类」里待着。
     *
     * 顺序是从「最不可能误判」到「最容易误判」：验证码几乎不会误判，
     * 而「消费」「活动」这种词在正常聊天里也常见，所以放后面。
     */
    fun classify(body: String): String {
        val s = body.trim()
        if (s.length < 3) return NONE

        if (CODE_WORDS.any { s.contains(it) } && DIGITS.containsMatchIn(s)) return CODE
        if (DELIVERY_WORDS.count { s.contains(it) } >= 1) return DELIVERY
        if (BILL_WORDS.count { s.contains(it) } >= 2) return BILL
        if (AD_WORDS.count { s.contains(it) } >= 2) return AD
        if (WORK_WORDS.any { s.contains(it) }) return WORK

        // 有内容、但都不是上面几类，归到「其他」。
        // 不返回 NONE，否则「未分类」会一直堆着几万条，用户以为功能坏了
        return if (s.length >= 6) LIFE else NONE
    }

    /** 中文名，界面显示用。 */
    fun label(tag: String): String = when (tag) {
        CODE -> "验证码"
        DELIVERY -> "快递"
        BILL -> "账单"
        AD -> "营销"
        WORK -> "工作"
        LIFE -> "其他"
        else -> "未分类"
    }
}
