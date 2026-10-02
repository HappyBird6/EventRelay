package dev.eventrelay.core

import java.text.Normalizer

data class SourceSelection(val kind: SourceKind, val identity: String) {
    init { require(identity.isNotBlank()) }
}

enum class MatchTarget { SOURCE_IDENTITY, TITLE, BODY, TITLE_AND_BODY, ORIGINAL_FIELD }
enum class MatchOperator { EQUALS, CONTAINS, NOT_CONTAINS }

/** All configured conditions are ANDed. A missing field never satisfies a condition. */
data class MatchCondition(
    val target: MatchTarget,
    val operator: MatchOperator,
    val value: String,
    val caseSensitive: Boolean = false,
    val originalField: String? = null,
) {
    init {
        require(value.isNotEmpty()) { "Empty match values are unsafe" }
        require(target != MatchTarget.ORIGINAL_FIELD || !originalField.isNullOrBlank())
    }

    fun matches(event: RelayEvent): Boolean {
        val candidate = when (target) {
            MatchTarget.SOURCE_IDENTITY -> event.sourceIdentity
            MatchTarget.TITLE -> event.title
            MatchTarget.BODY -> event.body
            MatchTarget.TITLE_AND_BODY -> listOfNotNull(event.title, event.body).joinToString("\n")
            MatchTarget.ORIGINAL_FIELD -> event.originalFields[originalField]?.let {
                if (it is EventValue.Text) it.value else it.toJson()
            }
        } ?: return false
        val ignoreCase = !caseSensitive
        return when (operator) {
            MatchOperator.EQUALS -> candidate.equals(value, ignoreCase)
            MatchOperator.CONTAINS -> candidate.contains(value, ignoreCase)
            MatchOperator.NOT_CONTAINS -> !candidate.contains(value, ignoreCase)
        }
    }
}

data class RelayPolicy(
    val allowedSources: Set<SourceSelection>,
    val conditions: List<MatchCondition>,
    val ownPackageName: String,
    val paused: Boolean = false,
    val additionalSensitiveTerms: Set<String> = emptySet(),
) {
    init { require(ownPackageName.isNotBlank()); require(additionalSensitiveTerms.none { it.isBlank() }) }
}

enum class RejectionReason { PAUSED, LOOP_SOURCE, SOURCE_NOT_ALLOWED, NO_RULES, SENSITIVE_CONTENT, RULES_NOT_MATCHED }

sealed class PolicyDecision {
    data class Approved(val event: RelayEvent, val eventId: String) : PolicyDecision()
    /** Deliberately carries neither event content nor the matched sensitive fragment. */
    data class Rejected(val reason: RejectionReason) : PolicyDecision()
}

object PolicyEngine {
    private val blockedGmailPackages = setOf("com.google.android.gm", "com.google.android.gm.lite")

    fun evaluate(event: RelayEvent, policy: RelayPolicy): PolicyDecision {
        if (policy.paused) return PolicyDecision.Rejected(RejectionReason.PAUSED)
        if (event.kind == SourceKind.NOTIFICATION &&
            (event.sourceIdentity == policy.ownPackageName || event.sourceIdentity in blockedGmailPackages)) {
            return PolicyDecision.Rejected(RejectionReason.LOOP_SOURCE)
        }
        if (SourceSelection(event.kind, event.sourceIdentity) !in policy.allowedSources)
            return PolicyDecision.Rejected(RejectionReason.SOURCE_NOT_ALLOWED)
        if (policy.conditions.isEmpty()) return PolicyDecision.Rejected(RejectionReason.NO_RULES)
        if (PrivacyGuard.isSensitive(event, policy.additionalSensitiveTerms))
            return PolicyDecision.Rejected(RejectionReason.SENSITIVE_CONTENT)
        if (!policy.conditions.all { it.matches(event) })
            return PolicyDecision.Rejected(RejectionReason.RULES_NOT_MATCHED)
        val snapshot = event.snapshot()
        return PolicyDecision.Approved(snapshot, snapshot.eventId)
    }
}

/**
 * Conservative defense in depth, not a claim of perfect secret detection. Keep sources narrowly
 * allowlisted, and add service-specific exclusion terms when necessary. Checks nested originals too.
 */
object PrivacyGuard {
    private val koreanCredentialContext = Regex(
        "인증\\s*(?:번호|코드)|확인\\s*(?:번호|코드)|비밀\\s*번호|보안\\s*(?:번호|코드)|일회용|" +
            "로그인\\s*(?:번호|코드)|복구\\s*코드|주민\\s*등록\\s*번호|" +
            "접근\\s*토큰|개인\\s*키|(?:OTP|2FA|MFA)(?![A-Za-z])",
        RegexOption.IGNORE_CASE,
    )
    private val credentialContext = Regex(
        "\\b(?:otp|one[ _-]?time[ _-]?(?:password|passcode|code)|verification[ _-]?code|" +
            "security[ _-]?code|authentication[ _-]?code|authorization[ _-]?code|" +
            "login[ _-]?code|sign[ _-]?in[ _-]?code|two[ _-]?factor|2fa|mfa|" +
            "password|passcode|pin|cvv|cvc|recovery[ _-]?code|backup[ _-]?code|" +
            "reset[ _-]?(?:code|link)|secret[ _-]?key|api[ _-]?key|access[ _-]?token)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val standaloneCode = Regex("(?<![0-9])[0-9]{4,8}(?![0-9])")
    private val codeContext = Regex("\\b(?:code|verify|verification|authenticate|confirm\\s+(?:your|identity)|do\\s+not\\s+share)\\b", RegexOption.IGNORE_CASE)
    private val socialSecurity = Regex("(?<![0-9])[0-9]{3}-[0-9]{2}-[0-9]{4}(?![0-9])")
    private val cardCandidate = Regex("(?<![0-9])(?:[0-9][ -]?){12,18}[0-9](?![0-9])")
    private val invisibleFormatting = Regex("[\\u200B-\\u200D\\uFEFF]")

    fun isSensitive(event: RelayEvent, additionalTerms: Set<String> = emptySet()): Boolean {
        val fields = (listOfNotNull(event.title, event.body) + EventValue.Object(event.originalFields).textValues()).map { text ->
            // Normalize a detection-only copy; the preserved event is never rewritten.
            invisibleFormatting.replace(Normalizer.normalize(text, Normalizer.Form.NFKC), "")
        }
        if (fields.any { standaloneCode.containsMatchIn(it) } && fields.any { codeContext.containsMatchIn(it) }) return true
        return fields.any { text ->
            credentialContext.containsMatchIn(text) || koreanCredentialContext.containsMatchIn(text) || socialSecurity.containsMatchIn(text) ||
                (standaloneCode.containsMatchIn(text) && codeContext.containsMatchIn(text)) ||
                additionalTerms.any { text.contains(it, ignoreCase = true) } ||
                cardCandidate.findAll(text).any { luhn(it.value.filter(Char::isDigit)) }
        }
    }

    private fun luhn(digits: String): Boolean {
        if (digits.length !in 13..19 || digits.all { it == digits[0] }) return false
        val sum = digits.reversed().mapIndexed { index, char ->
            val digit = char.digitToInt()
            if (index % 2 == 1) (digit * 2).let { if (it > 9) it - 9 else it } else digit
        }.sum()
        return sum % 10 == 0
    }
}
