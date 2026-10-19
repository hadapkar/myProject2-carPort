package dev.hadapkar.carport.patcher

/** A patch failure whose message is written for the user. */
class PatchException(message: String, cause: Throwable? = null) : Exception(message, cause)
