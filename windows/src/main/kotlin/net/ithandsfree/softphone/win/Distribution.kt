package net.ithandsfree.softphone.win

/**
 * Same two products as the Android app. GPL-2.0.
 * IHF Phone is the branded customer build. Softphone, the Community edition, leaves the
 * server empty until the user enters the BFF URL and SIP domain.
 */
enum class Distribution {
    IHF,
    COMMUNITY;

    companion object {
        fun from(raw: String?): Distribution = when (raw?.trim()?.lowercase()) {
            "community" -> COMMUNITY
            else -> IHF
        }
    }
}

data class DistributionProfile(
    val id: Distribution,
    val productName: String,
    val brandSub: String,
    val footerCaption: String,
    val userAgentName: String,
    val serverEditable: Boolean,
    val defaultApiBase: String,
    val defaultSipDomain: String,
)

fun distributionProfile(id: Distribution): DistributionProfile = when (id) {
    Distribution.IHF -> DistributionProfile(
        id = id,
        productName = "IHF Phone",
        brandSub = "IT HANDS FREE",
        footerCaption = "Canadian-hosted Cloud PBX",
        userAgentName = "IHF-Softphone",
        serverEditable = false,
        defaultApiBase = HostConfig.PLACEHOLDER_API,
        defaultSipDomain = HostConfig.PLACEHOLDER_SIP,
    )
    Distribution.COMMUNITY -> DistributionProfile(
        id = id,
        productName = "Softphone",
        brandSub = "COMMUNITY EDITION",
        footerCaption = "Works with FreePBX",
        userAgentName = "Community-Softphone",
        serverEditable = true,
        defaultApiBase = "",
        defaultSipDomain = "",
    )
}

fun resolveHost(
    profile: DistributionProfile,
    envApi: String?,
    envSip: String?,
    fileApi: String?,
    fileSip: String?,
): HostConfig {
    fun pick(env: String?, file: String?, fallback: String): String {
        val fromEnv = env?.trim().orEmpty()
        if (fromEnv.isNotEmpty()) return fromEnv
        val fromFile = file?.trim().orEmpty()
        if (fromFile.isNotEmpty()) return fromFile
        return fallback
    }
    return HostConfig(
        apiBase = pick(envApi, fileApi, profile.defaultApiBase),
        sipDomain = pick(envSip, fileSip, profile.defaultSipDomain),
    )
}
