package local.clashfarmer.core

/** A quote for the selected level. All prices come from the current game panel. */
data class WallBatchPlan(val currency: String, val count: Int, val unitPrice: Long) {
    val total: Long get() = Math.multiplyExact(unitPrice, count.toLong())

    companion object {
        fun hasCompleteEvidence(
            prices: Map<String, Long>,
            balances: Map<String, Long>,
            preference: String
        ): Boolean {
            val required = when (preference) {
                "auto" -> setOf("gold", "elixir")
                "gold", "elixir" -> setOf(preference)
                else -> return false
            }
            return required.all { currency ->
                prices[currency]?.let { it > 0 } == true &&
                    balances[currency]?.let { it >= 0 } == true
            }
        }

        fun choose(prices: Map<String, Long>, balances: Map<String, Long>, reserves: Map<String, Long>,
                   preference: String, limit: Int): WallBatchPlan? {
            require(preference in setOf("auto", "gold", "elixir"))
            require(limit in 1..100)
            return listOf("gold", "elixir").filter { preference == "auto" || it == preference }
                .mapNotNull { currency ->
                    val price = prices[currency]?.takeIf { it > 0 } ?: return@mapNotNull null
                    val balance = balances[currency]?.takeIf { it >= 0 } ?: return@mapNotNull null
                    val reserve = reserves[currency]?.takeIf { it >= 0 } ?: return@mapNotNull null
                    val available = if (balance > reserve) balance - reserve else 0
                    val count = (available / price).coerceAtMost(limit.toLong()).toInt()
                    if (count > 0) WallBatchPlan(currency, count, price) else null
                }.maxByOrNull { it.count }
        }

        fun nextStep(current: Int, target: Int): Int = when {
            current < 1 || target < current -> 0
            target - current >= 10 -> 10
            target > current -> 1
            else -> 0
        }

        fun acceptsGrowth(before: Int, after: Int, step: Int, target: Int): Boolean =
            step in listOf(1, 10) && after == before + step && after <= target
    }
}
