package app.nuta.spotify

import app.nuta.core.security.SecretValue

data class SpotifyWebToken(
    val value: SecretValue,
    val expiresAtMs: Long,
)
