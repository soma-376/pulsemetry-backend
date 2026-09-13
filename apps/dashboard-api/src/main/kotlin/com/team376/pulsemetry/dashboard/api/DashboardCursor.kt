package com.team376.pulsemetry.dashboard.api

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

internal fun encodeCursor(id: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray(StandardCharsets.UTF_8))
internal fun decodeCursor(raw: String): UUID {
    require(raw.length <= 100)
    return UUID.fromString(String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8))
}
