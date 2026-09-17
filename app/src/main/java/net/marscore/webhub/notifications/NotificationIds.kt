package net.marscore.webhub.notifications

/**
 * Stable per-(childId, tag) notification id computation (task 6.3).
 *
 * Same (childId, tag) always yields the same id → replacing semantics within one child.
 * The mix folds childId's 64 bits into 32 so different children don't collide on the same tag.
 *
 * Kept as a top-level internal helper so it is unit-testable without Android.
 */
internal object NotificationIds {

    /**
     * `31 * mix(childId) + tag.hashCode()` where `mix` folds the high 32 bits of [childId] into
     * the low 32 with xor. Result is coerced to a non-zero positive int (notification id 0 is
     * indistinguishable from "no notification" in some platform paths, so we avoid it).
     */
    fun forTag(childId: Long, tag: String): Int {
        val childMix = (childId xor (childId ushr 32)).toInt()
        val id = 31 * childMix + tag.hashCode()
        return when (id == 0) {
            true -> 1
            else -> id and 0x7fffffff
        }
    }
}