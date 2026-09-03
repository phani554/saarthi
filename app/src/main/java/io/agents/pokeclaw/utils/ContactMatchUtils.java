// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.utils;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared deterministic matching rules for contact names, aliases, and phone numbers.
 * Enforces strict exact-word / boundary matching to eliminate false substring matches
 * (e.g. preventing "Moments" or "Tomorrow" from matching "Mom", or "Kamyab" from matching "Kamya").
 */
public final class ContactMatchUtils {
    private ContactMatchUtils() {}

    public static String normalizeText(String value) {
        if (value == null) return "";
        return value
            .trim()
            .replaceAll("[^\\p{L}\\p{Nd}]+", " ")
            .replaceAll("\\s+", " ")
            .trim()
            .toLowerCase(Locale.ROOT);
    }

    public static String digitsOnly(String value) {
        if (value == null) return "";
        return value.replaceAll("\\D+", "");
    }

    public static LinkedHashSet<String> buildNormalizedAliases(String rawTarget) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        for (String candidate : splitCandidates(rawTarget)) {
            addNormalizedAlias(aliases, candidate);
        }
        addNormalizedAlias(aliases, rawTarget);
        return aliases;
    }

    public static LinkedHashSet<String> buildDigitAliases(String rawTarget) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        for (String candidate : splitCandidates(rawTarget)) {
            addDigitAlias(aliases, candidate);
        }
        addDigitAlias(aliases, rawTarget);
        return aliases;
    }

    public static boolean matchesTarget(CharSequence text, CharSequence description, String rawTarget) {
        LinkedHashSet<String> normalizedAliases = buildNormalizedAliases(rawTarget);
        LinkedHashSet<String> digitAliases = buildDigitAliases(rawTarget);
        return matchesTarget(text, description, normalizedAliases, digitAliases);
    }

    public static boolean matchesTarget(
        CharSequence text,
        CharSequence description,
        Set<String> normalizedAliases,
        Set<String> digitAliases
    ) {
        return matchesCandidate(text != null ? text.toString() : null, normalizedAliases, digitAliases) ||
            matchesCandidate(description != null ? description.toString() : null, normalizedAliases, digitAliases);
    }

    public static boolean matchesCandidate(
        String candidate,
        Set<String> normalizedAliases,
        Set<String> digitAliases
    ) {
        if (candidate == null || candidate.isBlank()) return false;

        String normalizedCandidate = normalizeText(candidate);
        if (normalizedCandidate.isEmpty()) return false;

        for (String alias : normalizedAliases) {
            if (alias.isEmpty()) continue;

            // 1. Exact full text match
            if (normalizedCandidate.equals(alias)) {
                return true;
            }

            // 2. Candidate starts with alias followed by word boundary (e.g. "Kamya Gupta" starts with "kamya ")
            if (normalizedCandidate.startsWith(alias + " ")) {
                return true;
            }

            // 3. Exact word boundary match (prevents "Tomorrow" from matching "mom")
            if (normalizedCandidate.matches(".*\\b" + Pattern.quote(alias) + "\\b.*")) {
                return true;
            }
        }

        String candidateDigits = digitsOnly(candidate);
        for (String alias : digitAliases) {
            if (alias.isEmpty()) continue;
            if (candidateDigits.equals(alias)) {
                return true;
            }
            if (alias.length() >= 7 && candidateDigits.endsWith(alias)) {
                return true;
            }
        }

        return false;
    }

    private static void addNormalizedAlias(Set<String> aliases, String candidate) {
        String normalized = normalizeText(candidate);
        if (!normalized.isEmpty()) {
            aliases.add(normalized);
        }
    }

    private static void addDigitAlias(Set<String> aliases, String candidate) {
        String digits = digitsOnly(candidate);
        if (digits.length() < 6) return;

        aliases.add(digits);
        if (digits.length() > 10) {
            aliases.add(digits.substring(digits.length() - 10));
        }
        if (digits.length() > 8) {
            aliases.add(digits.substring(digits.length() - 8));
        }
    }

    private static String[] splitCandidates(String rawTarget) {
        if (rawTarget == null || rawTarget.isBlank()) return new String[0];
        return rawTarget.split("[|,;/]+");
    }
}
