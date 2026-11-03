package com.rmcheck.app

const val MAX_HOSTS = 1024   // largest range accepted in one scan (a /22)

val DEFAULT_PORTS = listOf(
    80, 81, 88, 443, 444, 8000, 8080, 8081, 8082, 8088, 8090,
    8443, 8444, 8888, 9000, 9090, 7547, 4567, 5000, 10000
)

const val IP_EXAMPLES = "e.g.   10.11.0.1     10.11.0.1-50     10.11.0.0/24     10.11.0.*"

class TooManyHosts(val count: Long) : Exception()

data class Parsed(val hosts: List<String>, val error: String?)

private val V4_REGEX = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
private val WILDCARD = Regex("""^(\d{1,3}\.\d{1,3}\.\d{1,3})\.\*$""")
private val CIDR = Regex("""^[0-9a-fA-F:.]+/\d{1,3}$""")
private val RANGE = Regex("""^[0-9a-fA-F:.]+-[0-9a-fA-F:.]+$""")
private val LAST_OCTET = Regex("""^\d{1,3}$""")
private val V6_CHARS = Regex("""^[0-9a-fA-F:]+$""")

fun parseV4(s: String): Long? {
    val m = V4_REGEX.matchEntire(s) ?: return null
    var v = 0L
    for (i in 1..4) {
        val octet = m.groupValues[i].toInt()
        if (octet > 255) return null
        v = (v shl 8) or octet.toLong()
    }
    return v
}

fun formatV4(v: Long): String =
    "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"

/** Plain IPv6 validation (no DNS lookups). Embedded IPv4 notation is not supported. */
private fun isV6(s: String): Boolean {
    if (!s.contains(':') || !V6_CHARS.matches(s)) return false
    val halves = s.split("::")
    if (halves.size > 2) return false
    val groups = halves.flatMap { h -> if (h.isEmpty()) emptyList() else h.split(":") }
    if (groups.any { it.isEmpty() || it.length > 4 }) return false
    return if (halves.size == 2) groups.size <= 7 else groups.size == 8
}

/** Clean pasted text (http://1.2.3.4:80/, [::1], spaces) and return a valid IP string, or null. */
fun normalizeIp(text: String): String? {
    var t = text.trim()
    t = t.replace(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://"), "")   // drop http:// or https://
    t = t.split("/")[0].split("?")[0].trim()                   // drop path
    if (t.startsWith("[")) {                                   // [ipv6] or [ipv6]:port
        t = t.substring(1).split("]")[0]
    } else if (t.count { it == ':' } == 1) {                   // ipv4:port
        t = t.split(":")[0]
    }
    t = t.split("%")[0]                                        // drop IPv6 zone id
    val v4 = parseV4(t)
    if (v4 != null) return formatV4(v4)
    if (isV6(t)) return t.lowercase()
    return null
}

fun sortHosts(list: List<String>): List<String> =
    list.sortedWith(
        compareBy<String>(
            { if (parseV4(it) != null) 0 else 1 },
            { parseV4(it) ?: 0L },
            { it }
        )
    )

fun urlHost(host: String): String = if (host.contains(':')) "[$host]" else host

/** Port list such as "80,443,8888" -> sorted unique ports, or null if invalid. */
fun parsePorts(text: String): List<Int>? {
    val parts = text.replace(" ", "").split(",").filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    val out = sortedSetOf<Int>()
    for (p in parts) {
        val n = p.toIntOrNull() ?: return null
        if (n < 1 || n > 65535) return null
        out.add(n)
    }
    return out.toList()
}

/**
 * Accepts: single IP, a.b.c.d-N, a.b.c.d-e.f.g.h, CIDR (a.b.c.0/24), a.b.c.*,
 * and lists separated by comma, space or new line. IPv6 is supported for single addresses.
 */
fun parseTargets(text: String): Parsed {
    val tokens = text.trim().split(Regex("[,\\s;]+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return Parsed(emptyList(), "Please enter or paste an IP address first.")

    val hosts = ArrayList<String>()
    val seen = HashSet<String>()
    fun add(ip: String) {
        if (seen.add(ip)) hosts.add(ip)
        if (hosts.size > MAX_HOSTS) throw TooManyHosts(hosts.size.toLong())
    }

    for (raw in tokens) {
        var tok = raw
        try {
            val wild = WILDCARD.matchEntire(tok)
            if (wild != null) tok = wild.groupValues[1] + ".0/24"

            if (CIDR.matches(tok)) {
                val parts = tok.split("/")
                val start = parseV4(parts[0]) ?: throw IllegalArgumentException()
                val prefix = parts[1].toInt()
                if (prefix < 0 || prefix > 32) throw IllegalArgumentException()
                val count = 1L shl (32 - prefix)
                if (count > MAX_HOSTS + 2L) throw TooManyHosts(count)
                val network = start and (count - 1).inv() and 0xFFFFFFFFL
                if (count <= 2L) {
                    for (i in 0L until count) add(formatV4(network + i))
                } else {
                    for (i in 1L until count - 1) add(formatV4(network + i))
                }
            } else if (RANGE.matches(tok)) {
                val parts = tok.split("-")
                val a = parts[0]
                val b = parts[1]
                val start = parseV4(a) ?: throw IllegalArgumentException()
                val end: Long? = if (LAST_OCTET.matches(b)) {
                    parseV4(a.split(".").take(3).joinToString(".") + "." + b)
                } else {
                    parseV4(b)
                }
                if (end == null || end < start) throw IllegalArgumentException()
                val count = end - start + 1
                if (count > MAX_HOSTS) throw TooManyHosts(count)
                for (i in 0L until count) add(formatV4(start + i))
            } else {
                val ip = normalizeIp(tok) ?: throw IllegalArgumentException()
                add(ip)
            }
        } catch (e: TooManyHosts) {
            return Parsed(emptyList(), "Range too large (${e.count} addresses). Maximum is $MAX_HOSTS hosts per scan.")
        } catch (e: IllegalArgumentException) {
            return Parsed(emptyList(), "Invalid entry: $raw   Example: 10.11.0.1, 10.11.0.1-50 or 10.11.0.0/24")
        }
    }
    return Parsed(sortHosts(hosts), null)
}
