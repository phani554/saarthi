// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

/**
 * File-name rules for a model imported from the phone's storage ("Import .litertlm file"). Pure, so it is
 * unit-tested without Android.
 */
object ModelImportNames {

    const val EXTENSION = ".litertlm"
    private const val MAX_BASE_LENGTH = 120

    /**
     * Safe file name inside the model folder for a picked document called [displayName], or null when it
     * is not a .litertlm file. Path separators and odd characters become "_", so the copy can never land
     * outside the model folder.
     */
    fun targetFileName(displayName: String?): String? {
        val raw = displayName?.trim().orEmpty().substringAfterLast('/').substringAfterLast('\\')
        if (!raw.endsWith(EXTENSION, ignoreCase = true)) return null
        val base = raw.dropLast(EXTENSION.length)
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trimStart('.')
            .take(MAX_BASE_LENGTH)
        if (base.isBlank() || base.all { it == '_' || it == '.' }) return null
        return base + EXTENSION
    }

    /** Temporary name while copying; the finished copy is renamed to [targetFileName]. */
    fun tempFileName(target: String): String = "$target.importing"
}
