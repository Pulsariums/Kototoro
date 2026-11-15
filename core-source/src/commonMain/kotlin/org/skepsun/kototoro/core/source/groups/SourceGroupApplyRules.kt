package org.skepsun.kototoro.core.source.groups

internal fun groupLockPlan(
	groupLocks: Map<String, Boolean?>,
	previouslyApplied: Set<String>,
): Map<String, Boolean?> {
	val released: Map<String, Boolean?> = previouslyApplied.filterNot { it in groupLocks.keys }.associateWith { null }
	val effective = groupLocks.filter { (name, lock) -> lock != null || name in previouslyApplied }
	return released + effective
}

internal fun groupPinsToRemove(
	previouslyApplied: Set<String>,
	inGroup: Set<String>,
	currentlyPinned: Set<String>,
): Set<String> = previouslyApplied.filterTo(HashSet()) { it !in inGroup && it in currentlyPinned }

internal fun groupAppliedPinsToStore(
	groupPinned: Set<String>,
	previouslyApplied: Set<String>,
	pinnedBefore: Set<String>,
): Set<String> = groupPinned.filterTo(HashSet()) { it !in pinnedBefore || it in previouslyApplied }

internal fun groupJarPriorityToApply(groupJarPriority: List<String>, installedJarNames: Set<String>): String? {
	val filtered = groupJarPriority.filter { it.trim().lowercase() in installedJarNames }
	return filtered.takeIf { it.isNotEmpty() }?.joinToString(",")
}

private val GROUP_SOURCE_NAME = Regex("[A-Za-z0-9_.:-]{1,128}")
private val GROUP_DOMAIN_HOST = Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?(?::\\d{1,5})?")

internal fun isValidGroupSourceName(name: String, knownNames: Set<String>): Boolean =
	GROUP_SOURCE_NAME.matches(name) && !name.contains("..") && name in knownNames

internal fun isValidGroupDomain(domain: String): Boolean {
	if (domain.length > 253 || domain.any { it.isWhitespace() }) return false
	val host = domain.removePrefix("https://")
	if (host.contains("://") || host.contains('/') || host.contains('@') || host.contains('\\')) return false
	return GROUP_DOMAIN_HOST.matches(host) && !host.startsWith("..") && !host.contains("..")
}
