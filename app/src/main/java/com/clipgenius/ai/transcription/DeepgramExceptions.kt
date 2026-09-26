package com.clipgenius.ai.transcription

/**
 * Thrown when Deepgram returns HTTP 401 or 403 unauthorized.
 */
class DeepgramAuthException(
    message: String = "Deepgram rejected the API key. Check the key in Settings."
) : Exception(message)

/**
 * Thrown when Deepgram returns HTTP 402 or billing / payment / quota exhaustion.
 */
class DeepgramQuotaException(
    message: String = "Your Deepgram key has hit its limit. Recharge your Deepgram account or replace the key in Settings."
) : Exception(message)
