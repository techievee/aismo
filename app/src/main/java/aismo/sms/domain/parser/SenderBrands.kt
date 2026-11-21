package aismo.sms.domain.parser

import aismo.sms.domain.SmsCategory

/** Small built-in dictionary mapping business sender tokens to a display name and a likely category. */
object SenderBrands {
    data class Brand(val name: String, val category: SmsCategory)

    private val brands: Map<String, Brand> = buildMap {
        fun bank(vararg tokens: String, name: String) = tokens.forEach { put(it, Brand(name, SmsCategory.FINANCE)) }
        fun brand(vararg tokens: String, name: String, category: SmsCategory) =
            tokens.forEach { put(it, Brand(name, category)) }

        bank("HDFCBK", "HDFCBANK", name = "HDFC Bank")
        bank("SBIINB", "SBIPSG", "SBIUPI", "SBICRD", name = "State Bank of India")
        bank("ICICIB", "ICICIT", name = "ICICI Bank")
        bank("AXISBK", "AXISBN", name = "Axis Bank")
        bank("KOTAKB", "KOTAKM", name = "Kotak Bank")
        bank("PNBSMS", "PNBBNK", name = "Punjab National Bank")
        bank("BOIIND", "BOIBNK", name = "Bank of India")
        bank("CANBNK", name = "Canara Bank")
        bank("IDFCFB", "IDFCBK", name = "IDFC First Bank")
        bank("YESBNK", "YESBK", name = "Yes Bank")
        bank("PAYTMB", "PAYTM", name = "Paytm")
        bank("GPAY", "GOOGLP", name = "Google Pay")
        bank("PHONPE", "PHNEPE", name = "PhonePe")
        bank("AMEXIN", "AMEX", name = "American Express")
        bank("CBA", "COMMBK", "COMMONWEALTH", name = "Commonwealth Bank")
        bank("NAB", name = "NAB")
        bank("ANZ", "ANZBANK", name = "ANZ")
        bank("WESTPAC", "WBC", name = "Westpac")
        bank("CHASE", name = "Chase")
        bank("BOFA", "BANKOFAMERICA", name = "Bank of America")
        bank("WELLSFARGO", name = "Wells Fargo")
        bank("PAYPAL", name = "PayPal")
        bank("VENMO", name = "Venmo")

        brand("AMAZON", "AMZNIN", "AMAZON", name = "Amazon", category = SmsCategory.ORDER)
        brand("FLPKRT", "FKRTIN", name = "Flipkart", category = SmsCategory.ORDER)
        brand("MYNTRA", name = "Myntra", category = SmsCategory.ORDER)
        brand("MEESHO", name = "Meesho", category = SmsCategory.ORDER)
        brand("SWIGGY", "SWIGGI", name = "Swiggy", category = SmsCategory.ORDER)
        brand("ZOMATO", name = "Zomato", category = SmsCategory.ORDER)
        brand("BLUDRT", "DELHVY", "ECOMEX", "XPBEES", name = "Courier", category = SmsCategory.ORDER)
        brand("AUSPOST", "DHL", "FEDEX", "UPS", name = "Courier", category = SmsCategory.ORDER)
        brand("UBER", "OLACAB", "OLAMNY", name = "Rides", category = SmsCategory.SERVICE)

        brand("IRCTC", "INDRAIL", name = "IRCTC", category = SmsCategory.TRAVEL)
        brand("MAKEMT", "MMTRIP", name = "MakeMyTrip", category = SmsCategory.TRAVEL)
        brand("REDBUS", name = "redBus", category = SmsCategory.TRAVEL)
        brand("INDIGO", "SPICEJT", "AIRIND", "QANTAS", "VIRGIN", name = "Airline", category = SmsCategory.TRAVEL)

        brand("AIRTEL", "ARTLPR", "AIRTLP", name = "Airtel", category = SmsCategory.SERVICE)
        brand("JIOINF", "JIOPAY", "JIO", name = "Jio", category = SmsCategory.SERVICE)
        brand("VODAFN", "VILIND", "VIINFO", name = "Vi", category = SmsCategory.SERVICE)
        brand("TELSTRA", "OPTUS", "VODAFONE", name = "Telecom", category = SmsCategory.SERVICE)

        brand("UIDAI", "AADHAR", name = "UIDAI", category = SmsCategory.GOVERNMENT)
        brand("INCTAX", "ITDEPT", name = "Income Tax Dept", category = SmsCategory.GOVERNMENT)
        brand("MYGOV", "ATO", "MEDICARE", "CENTRELINK", name = "Government", category = SmsCategory.GOVERNMENT)
        brand("EPFOHO", "DIGLKR", name = "Government", category = SmsCategory.GOVERNMENT)
    }

    fun lookup(token: String): Brand? = brands[token.uppercase().filter { it.isLetterOrDigit() }]

    /** Human-friendly title for a business sender token. */
    fun displayName(token: String): String {
        lookup(token)?.let { return it.name }
        val t = token.trim()
        return if (t.length in 4..14 && t.all { it.isUpperCase() || it.isDigit() }) {
            t.lowercase().replaceFirstChar { it.uppercase() }
        } else {
            t
        }
    }
}
