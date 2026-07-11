package com.leaflock.nfctap

/**
 * Fixed Paradise Centre pop-up products — matches the web POS.
 */
data class PosProduct(
    val code: String,
    val label: String,
    val priceAud: Double?,
    val detail: String,
) {
    override fun toString(): String =
        if (priceAud != null) "$label — $${"%.2f".format(priceAud)}" else label
}

object ProductCatalog {
    val products: List<PosProduct> = listOf(
        PosProduct("family", "Family Kit", 80.0, "PUSH · 2 mixes, 2 moulds, cup, keychain"),
        PosProduct("all4", "Family Kit + All 4 Flavours", 130.0, "Premium · all 4 mixes + kit"),
        PosProduct("two", "2 Flavours", 50.0, "Basic deal · 2 mix bags"),
        PosProduct("ready", "Complete Ready Kit", 70.0, "2 mixes, 1 mould, cup, keychain"),
        PosProduct("mix1", "1 Mix", 30.0, "Single flavour bag"),
        PosProduct("extra2", "Add 2 Extra Flavours", 50.0, "Upsell from Family Kit"),
        PosProduct("extra1", "Add 1 Extra Flavour", 25.0, "One extra bag"),
        PosProduct("mould", "Extra Mould", 9.99, "1 silicone mould"),
        PosProduct("mould2", "2 Extra Moulds", 17.0, "2 silicone moulds"),
        PosProduct("keychain", "Extra Keychain", 5.0, "Whisk keychain"),
        PosProduct("sticker", "Sticker", 2.0, "1 sticker"),
        PosProduct("stickers3", "3 Stickers", 5.0, "3 stickers"),
        PosProduct("magnet", "Magnet", 3.0, "1 magnet"),
        PosProduct("magnets2", "2 Magnets", 5.0, "2 magnets"),
        PosProduct("merch", "Mini Merch Pack", 5.0, "1 magnet + 2 stickers"),
        PosProduct("custom", "Custom amount only", null, "Uses amount field → /?amount="),
    )
}
