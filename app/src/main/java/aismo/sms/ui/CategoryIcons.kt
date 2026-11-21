package aismo.sms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import aismo.sms.domain.SmsCategory
import aismo.sms.ui.theme.categoryColor

/** An icon a custom category can wear: what is stored, what the picker calls it, and the picture. */
class CategoryIconChoice(val key: String, val label: String, val icon: ImageVector)

/** Every choice, in [CategoryIconKeys.ALL] order; a unit test keeps the two lists in step. */
val CategoryIconChoices: List<CategoryIconChoice> = listOf(
    CategoryIconChoice("label", "Tag", Icons.Filled.Label),
    CategoryIconChoice("star", "Star", Icons.Filled.Star),
    CategoryIconChoice("heart", "Heart", Icons.Filled.Favorite),
    CategoryIconChoice("home", "Home", Icons.Filled.Home),
    CategoryIconChoice("family", "Family", Icons.Filled.FamilyRestroom),
    CategoryIconChoice("child", "Kids", Icons.Filled.ChildCare),
    CategoryIconChoice("work", "Work", Icons.Filled.Work),
    CategoryIconChoice("school", "School", Icons.Filled.School),
    CategoryIconChoice("cart", "Shopping", Icons.Filled.ShoppingCart),
    CategoryIconChoice("food", "Food", Icons.Filled.Restaurant),
    CategoryIconChoice("coffee", "Coffee", Icons.Filled.LocalCafe),
    CategoryIconChoice("subscription", "Subscriptions", Icons.Filled.Subscriptions),
    CategoryIconChoice("flight", "Flights", Icons.Filled.Flight),
    CategoryIconChoice("luggage", "Trips", Icons.Filled.Luggage),
    CategoryIconChoice("car", "Car", Icons.Filled.DirectionsCar),
    CategoryIconChoice("train", "Train", Icons.Filled.Train),
    CategoryIconChoice("fitness", "Fitness", Icons.Filled.FitnessCenter),
    CategoryIconChoice("medical", "Medical", Icons.Filled.MedicalServices),
    CategoryIconChoice("pets", "Pets", Icons.Filled.Pets),
    CategoryIconChoice("music", "Music", Icons.Filled.MusicNote),
    CategoryIconChoice("movie", "Movies", Icons.Filled.Movie),
    CategoryIconChoice("games", "Games", Icons.Filled.SportsEsports),
    CategoryIconChoice("sports", "Sports", Icons.Filled.SportsSoccer),
    CategoryIconChoice("news", "News", Icons.Filled.Newspaper),
    CategoryIconChoice("bank", "Bank", Icons.Filled.AccountBalance),
    CategoryIconChoice("money", "Money", Icons.Filled.Payments),
    CategoryIconChoice("savings", "Savings", Icons.Filled.Savings),
    CategoryIconChoice("bolt", "Electricity", Icons.Filled.Bolt),
    CategoryIconChoice("water", "Water", Icons.Filled.WaterDrop),
    CategoryIconChoice("phone", "Phone", Icons.Filled.Phone),
    CategoryIconChoice("mail", "Mail", Icons.Filled.Mail),
    CategoryIconChoice("event", "Events", Icons.Filled.Event),
    CategoryIconChoice("bookmark", "Bookmark", Icons.Filled.Bookmark),
    CategoryIconChoice("group", "People", Icons.Filled.Group),
    CategoryIconChoice("tools", "Tools", Icons.Filled.Build),
    CategoryIconChoice("idea", "Ideas", Icons.Filled.Lightbulb),
    CategoryIconChoice("cloud", "Cloud", Icons.Filled.Cloud),
    CategoryIconChoice("flag", "Flag", Icons.Filled.Flag),
    CategoryIconChoice("gift", "Gifts", Icons.Filled.CardGiftcard),
    CategoryIconChoice("trophy", "Trophy", Icons.Filled.EmojiEvents),
    CategoryIconChoice("eco", "Nature", Icons.Filled.Eco),
    CategoryIconChoice("shield", "Security", Icons.Filled.Shield),
)

/** The picture for a stored icon key; a missing or unknown key (an older or newer backup) is the plain tag. */
fun customCategoryIcon(key: String?): ImageVector = (CategoryIconChoices.firstOrNull { it.key == key } ?: CategoryIconChoices.first()).icon

/** The icon each custom category has chosen, by category key, for every screen that draws a category. */
val LocalCustomCategoryIcons = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

/** One icon per built-in category; a custom category wears the icon it was given. */
@Composable
fun categoryIcon(key: String): ImageVector = when (SmsCategory.fromKey(key)) {
    SmsCategory.PERSONAL -> Icons.Filled.Person
    SmsCategory.OTP -> Icons.Filled.Key
    SmsCategory.FINANCE -> Icons.Filled.Payments
    SmsCategory.BILL -> Icons.Filled.Receipt
    SmsCategory.ORDER -> Icons.Filled.ShoppingBag
    SmsCategory.TRAVEL -> Icons.Filled.Flight
    SmsCategory.GOVERNMENT -> Icons.Filled.AccountBalance
    SmsCategory.HEALTH -> Icons.Filled.LocalHospital
    SmsCategory.EDUCATION -> Icons.Filled.School
    SmsCategory.JOB -> Icons.Filled.Work
    SmsCategory.SERVICE -> Icons.Filled.SupportAgent
    SmsCategory.PROMO -> Icons.Filled.LocalOffer
    SmsCategory.SPAM -> Icons.Filled.Report
    SmsCategory.OTHER -> Icons.Filled.Category
    null -> customCategoryIcon(LocalCustomCategoryIcons.current[key])
}

/** The category's icon in its own colour on a soft round background. */
@Composable
fun CategoryBadge(key: String, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val color = categoryColor(key)
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(categoryIcon(key), contentDescription = null, tint = color, modifier = Modifier.size(size * 0.55f))
    }
}
