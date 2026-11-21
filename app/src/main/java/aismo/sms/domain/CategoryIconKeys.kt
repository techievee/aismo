package aismo.sms.domain

/**
 * The icons a custom category can wear. These keys are what gets stored (in the database and in backups), so a key is never
 * renamed or reused; the screen maps each to a picture and a name. A key the app does not know, from a newer backup, shows [DEFAULT].
 */
object CategoryIconKeys {
    const val DEFAULT = "label"

    /** In the order the picker shows them, the default first. */
    val ALL: List<String> = listOf(
        "label", "star", "heart", "home", "family", "child", "work", "school", "cart", "food", "coffee", "subscription",
        "flight", "luggage", "car", "train", "fitness", "medical", "pets", "music", "movie", "games", "sports", "news",
        "bank", "money", "savings", "bolt", "water", "phone", "mail", "event", "bookmark", "group", "tools", "idea",
        "cloud", "flag", "gift", "trophy", "eco", "shield",
    )
}
