package com.mywatt.app.engine

import kotlin.math.floor
import kotlin.math.roundToInt

/** The physiological system a session works, used to group the library. */
enum class Region(val label: String) {
    Neuromuscular("Neuromuscular"),
    Anaerobic("Anaerobic"),
    VO2max("VO2max"),
    Threshold("Threshold"),
    Endurance("Endurance"),
    Test("Test"),
}

/** One block of a library session, with targets as percentages of FTP. */
sealed interface Step

/** Warm-up at [fromPercent], or a build from [fromPercent] to [toPercent]. */
data class WarmUpStep(val seconds: Int, val fromPercent: Int, val toPercent: Int = fromPercent) : Step

data class CoolDownStep(val seconds: Int, val percent: Int) : Step

data class RepStep(
    val seconds: Int,
    val percent: Int,
    val cadenceRpm: Int? = null,
    val bestWindowSeconds: Int? = null,
    val kind: RepKind = RepKind.INTERVAL,
    val set: Int = 0,
) : Step

/** A fixed recovery. */
data class EasyStep(val seconds: Int, val percent: Int) : Step

/** A recovery ended by the heart-rate gate. */
data object GatedStep : Step

/** A self-ending ramp; its watts are absolute, not a share of FTP. */
data class RampStep(val startWatts: Int, val stepWatts: Int, val stepSeconds: Int) : Step

/**
 * A session from the library: what it is, and its blocks with targets as % of FTP. Turn it
 * into something the [SessionRunner] can ride with [build].
 */
data class LibrarySession(
    val id: String,
    val name: String,
    val region: Region,
    val durationMinutes: Int,
    val oneLineWhy: String,
    val screenMode: ScreenMode,
    val blocks: List<Step>,
) {
    /** Which FTP test this is, if any: a ramp makes it the ramp test, a self-paced effort the 20-minute test. */
    val ftpTest: FtpTest?
        get() = when {
            blocks.any { it is RampStep } -> FtpTest.RAMP
            blocks.any { it is RepStep && it.kind == RepKind.SELF_PACED } -> FtpTest.TWENTY_MINUTE
            else -> null
        }

    /** The session at [ftpWatts], its gated recoveries using [setting]. */
    fun build(ftpWatts: Int, setting: RecoverySetting = RecoverySetting.DEFAULT): Session {
        fun w(percent: Int) = watts(percent, ftpWatts)
        val built = blocks.map { step ->
            when (step) {
                is WarmUpStep -> WarmUp(step.seconds, w(step.fromPercent), w(step.toPercent))
                is CoolDownStep -> CoolDown(step.seconds, w(step.percent))
                is RepStep -> Rep(step.seconds, w(step.percent), step.cadenceRpm, step.bestWindowSeconds, step.kind, step.set)
                is EasyStep -> Easy(step.seconds, w(step.percent))
                GatedStep -> Recovery(HrGate(setting, leadInSeconds = HrGate.DEFAULT_LEAD_IN_SECONDS))
                is RampStep -> Ramp(step.startWatts, step.stepWatts, step.stepSeconds)
            }
        }
        return Session(name, built, setting, id, ftpWatts, screenMode, ftpTest)
    }

    /**
     * How long the session takes for a rider at [ftpWatts], counting each gated recovery
     * as [Library.NOMINAL_GATED_SECONDS] and a ramp as running to a maximal power of FTP /
     * 0.75, half way through the step after the last one completed.
     */
    fun nominalSeconds(ftpWatts: Int = Library.NOMINAL_FTP): Int = blocks.sumOf { step ->
        when (step) {
            is WarmUpStep -> step.seconds
            is CoolDownStep -> step.seconds
            is RepStep -> step.seconds
            is EasyStep -> step.seconds
            GatedStep -> Library.NOMINAL_GATED_SECONDS
            is RampStep -> {
                val maximal = ftpWatts / FtpTest.RAMP.factor
                val fullSteps = (floor((maximal - step.startWatts) / step.stepWatts).toInt() + 1).coerceAtLeast(0)
                fullSteps * step.stepSeconds + step.stepSeconds / 2
            }
        }
    }

    companion object {
        /** [percent] of [ftpWatts], to the nearest watt. */
        fun watts(percent: Int, ftpWatts: Int): Int = (percent * ftpWatts / 100.0).roundToInt()
    }
}

/** A library text that does not parse; [message] says where. */
class LibraryParseException(message: String) : IllegalArgumentException(message)

/**
 * The session library, written as data in [SOURCE] and parsed on first use.
 *
 * Format: one session per paragraph, one `key: value` per line. Keys: id, name, region,
 * minutes, mode (Dial or NumberFirst), why, blocks; and optionally warmup and cooldown,
 * which default to `5m @55` and `3m @50` (`none` drops them).
 *
 * Blocks are comma-separated:
 * - `rep 1m @110` a rep at 110% FTP; may add `cad 60` (cadence to hold) or `best 10s`
 *   (a sprint scored on its best 10 s of raw watts)
 * - `steady 45m @65` one long block held at an effort
 * - `selfpaced 20m` a test effort ridden by feel, target shown as FTP
 * - `easy 3m @55` a fixed recovery
 * - `gated` a recovery ended by the heart-rate gate
 * - `ramp 100W +20W/1m` a self-ending ramp in absolute watts
 * - `N x [ ... ]` repeat; `N x [ ... ] between [ ... ]` puts the second part between
 *   repeats but not after the last. A repeat of repeats numbers its sets.
 * Durations are `30s` or `5m`; a warm-up may be a build, `10m @60-90`.
 */
object Library {

    /** Gated recoveries count as this long when working out a session's duration. */
    const val NOMINAL_GATED_SECONDS = 120

    /** The FTP durations are worked out at; only the ramp depends on it. */
    const val NOMINAL_FTP = 200

    private const val DEFAULT_WARMUP = "5m @55"
    private const val DEFAULT_COOLDOWN = "3m @50"

    val SOURCE = """
        id: three-by-one
        name: Three by One
        region: VO2max
        minutes: 17
        mode: NumberFirst
        why: Three hard minutes, and how fast your heart comes back after each.
        blocks: 3 x [rep 1m @110, gated]

        id: ronnestad-30-15
        name: Rønnestad 30/15
        region: VO2max
        minutes: 43
        mode: NumberFirst
        why: Short on/off bursts that keep you near VO2max for longer than steady reps.
        blocks: 3 x [13 x [rep 30s @120, easy 15s @60]] between [easy 3m @50]

        id: vo2-5x5
        name: VO2 5×5
        region: VO2max
        minutes: 43
        mode: Dial
        why: Five long VO2max reps to stretch your aerobic ceiling.
        blocks: 5 x [rep 5m @108, gated]

        id: vo2-3x3
        name: VO2 3×3
        region: VO2max
        minutes: 23
        mode: NumberFirst
        why: Three harder, shorter VO2max reps when time is tight.
        blocks: 3 x [rep 3m @115, gated]

        id: piano-fingers
        name: Piano Fingers
        region: Anaerobic
        minutes: 29
        mode: NumberFirst
        why: Seven minutes climbing in 5% steps to find where the legs give out.
        blocks: rep 1m @115, gated, rep 1m @120, gated, rep 1m @125, gated, rep 1m @130, gated, rep 1m @135, gated, rep 1m @140, gated, rep 1m @140, gated

        id: lead-weight
        name: Lead Weight
        region: Anaerobic
        minutes: 30
        mode: NumberFirst
        why: A heavy two minutes first, then rising one-minute efforts on tired legs.
        blocks: rep 2m @115, gated, rep 1m @120, gated, rep 1m @125, gated, rep 1m @130, gated, rep 1m @135, gated, rep 1m @140, gated, rep 1m @140, gated

        id: stepdown
        name: Stepdown
        region: VO2max
        minutes: 23
        mode: NumberFirst
        why: Start hard and hold on as the target falls, three minutes at a time.
        blocks: 3 x [rep 1m @130, rep 1m @117, rep 1m @105, gated]

        id: threshold-5x8
        name: Threshold 5×8
        region: Threshold
        minutes: 60
        mode: Dial
        why: Forty minutes just under FTP to lift what you can hold for an hour.
        blocks: 5 x [rep 8m @98] between [easy 3m @55]

        id: sweet-spot-3x10
        name: Sweet Spot 3×10
        region: Threshold
        minutes: 46
        mode: Dial
        why: Solid threshold work that you can recover from by tomorrow.
        blocks: 3 x [rep 10m @90] between [easy 4m @55]

        id: tempo-3x10
        name: Tempo 3×10
        region: Threshold
        minutes: 48
        mode: Dial
        why: Steady tempo to build the base under your threshold.
        blocks: 3 x [rep 10m @84] between [easy 5m @55]

        id: low-cadence-force
        name: Low-Cadence Force
        region: Threshold
        minutes: 40
        mode: Dial
        why: Big gear, slow legs: muscular strength at a sub-threshold load.
        blocks: 3 x [rep 8m @85 cad 60] between [easy 4m @55]

        id: neuro-sprints
        name: Neuro Sprints
        region: Neuromuscular
        minutes: 24
        mode: NumberFirst
        why: Six flat-out sprints with full rest, for peak power.
        blocks: 6 x [rep 14s @250 best 10s] between [easy 3m @50]

        id: tabata
        name: Tabata
        region: Anaerobic
        minutes: 12
        mode: NumberFirst
        why: Four minutes of 20/10 that empties the anaerobic tank.
        blocks: 8 x [rep 20s @170, easy 10s @40]

        id: z2-45
        name: Z2 45
        region: Endurance
        minutes: 53
        mode: Dial
        why: An easy aerobic ride that builds endurance without much fatigue.
        blocks: steady 45m @65

        id: test-ramp
        name: TEST Ramp
        region: Test
        minutes: 18
        mode: NumberFirst
        why: One-minute steps up 20 W until you can't hold them; FTP from your last full step.
        warmup: 5m @50
        blocks: ramp 100W +20W/1m

        id: test-20
        name: TEST 20-min
        region: Test
        minutes: 40
        mode: NumberFirst
        why: The classic: your best 20 minutes, with FTP set at 95% of it.
        warmup: 10m @60-90
        cooldown: 5m @50
        blocks: easy 5m @50, selfpaced 20m
    """.trimIndent()

    /** Every session, in library order. */
    val sessions: List<LibrarySession> by lazy { parse(SOURCE) }

    fun byId(id: String): LibrarySession? = sessions.firstOrNull { it.id == id }

    /** Parses a library text in the format above. */
    fun parse(text: String): List<LibrarySession> =
        text.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { parseSession(it) }
            .also { list ->
                val dupes = list.groupBy { it.id }.filterValues { it.size > 1 }.keys
                if (dupes.isNotEmpty()) throw LibraryParseException("duplicate id: ${dupes.joinToString()}")
            }

    private val KEYS = setOf("id", "name", "region", "minutes", "mode", "why", "blocks", "warmup", "cooldown")

    private fun parseSession(paragraph: String): LibrarySession {
        val fields = LinkedHashMap<String, String>()
        paragraph.lines().forEach { line ->
            val colon = line.indexOf(':')
            if (colon < 0) throw LibraryParseException("expected 'key: value', got '$line'")
            val key = line.substring(0, colon).trim()
            if (key !in KEYS) throw LibraryParseException("unknown key '$key'")
            if (key in fields) throw LibraryParseException("'$key' given twice")
            fields[key] = line.substring(colon + 1).trim()
        }
        fun need(key: String) = fields[key]?.takeIf { it.isNotEmpty() }
            ?: throw LibraryParseException("${fields["id"] ?: "session"}: missing '$key'")

        val id = need("id")
        if (!Regex("[a-z0-9-]+").matches(id)) throw LibraryParseException("$id: id must be lower-case letters, digits and dashes")
        try {
            val region = Region.entries.firstOrNull { it.name == need("region") }
                ?: throw LibraryParseException("unknown region '${fields["region"]}'")
            val mode = ScreenMode.entries.firstOrNull { it.name == need("mode") }
                ?: throw LibraryParseException("unknown mode '${fields["mode"]}'")
            val minutes = need("minutes").toIntOrNull()?.takeIf { it > 0 }
                ?: throw LibraryParseException("minutes must be a positive whole number")

            val steps = ArrayList<Step>()
            val warm = fields["warmup"] ?: DEFAULT_WARMUP
            if (warm != "none") steps += parseWarmUp(warm)
            steps += parseBlocks(need("blocks"))
            val cool = fields["cooldown"] ?: DEFAULT_COOLDOWN
            if (cool != "none") steps += parseCoolDown(cool)

            return LibrarySession(id, need("name"), region, minutes, need("why"), mode, steps)
        } catch (e: LibraryParseException) {
            throw LibraryParseException("$id: ${e.message}")
        }
    }

    private fun parseWarmUp(text: String): WarmUpStep {
        val parts = text.split(Regex("\\s+"))
        if (parts.size != 2) throw LibraryParseException("warm-up must be '<duration> @<pct>', got '$text'")
        val (from, to) = percentRange(parts[1])
        return WarmUpStep(duration(parts[0]), from, to)
    }

    private fun parseCoolDown(text: String): CoolDownStep {
        val parts = text.split(Regex("\\s+"))
        if (parts.size != 2) throw LibraryParseException("cool-down must be '<duration> @<pct>', got '$text'")
        return CoolDownStep(duration(parts[0]), percent(parts[1]))
    }

    // ---------------------------------------------------------------- blocks

    private sealed interface Node
    private class Leaf(val step: Step) : Node
    private class Repeat(val count: Int, val body: List<Node>, val between: List<Node>) : Node

    /** Parses a `blocks:` value into a flat list of steps. */
    fun parseBlocks(text: String): List<Step> {
        val tokens = text.replace("[", " [ ").replace("]", " ] ").replace(",", " , ")
            .split(Regex("\\s+")).filter { it.isNotEmpty() }
        val parser = BlockParser(tokens)
        val nodes = parser.sequence()
        if (!parser.atEnd()) throw LibraryParseException("unexpected '${parser.peek()}'")
        val out = ArrayList<Step>()
        nodes.forEach { expand(it, 0, out) }
        if (out.isEmpty()) throw LibraryParseException("no blocks")
        return out
    }

    private fun expand(node: Node, set: Int, out: MutableList<Step>) {
        when (node) {
            is Leaf -> out += (node.step as? RepStep)?.copy(set = set) ?: node.step
            is Repeat -> {
                val isSets = node.body.any { it is Repeat }
                repeat(node.count) { i ->
                    val thisSet = if (isSets) i + 1 else set
                    node.body.forEach { expand(it, thisSet, out) }
                    if (i < node.count - 1) node.between.forEach { expand(it, set, out) }
                }
            }
        }
    }

    private class BlockParser(private val tokens: List<String>) {
        private var pos = 0

        fun atEnd() = pos >= tokens.size
        fun peek(): String? = tokens.getOrNull(pos)
        private fun next(): String = tokens.getOrNull(pos++) ?: throw LibraryParseException("blocks end too soon")
        private fun expect(token: String) {
            val got = next()
            if (got != token) throw LibraryParseException("expected '$token', got '$got'")
        }

        fun sequence(): List<Node> {
            val nodes = arrayListOf(item())
            while (peek() == ",") {
                next()
                nodes += item()
            }
            return nodes
        }

        private fun bracketed(): List<Node> {
            expect("[")
            val inner = sequence()
            expect("]")
            return inner
        }

        private fun item(): Node {
            val word = next()
            val count = word.toIntOrNull()
            if (count != null) {
                if (count < 1) throw LibraryParseException("repeat count must be at least 1")
                expect("x")
                val body = bracketed()
                val between = if (peek() == "between") {
                    next()
                    bracketed()
                } else {
                    emptyList()
                }
                return Repeat(count, body, between)
            }
            return Leaf(
                when (word) {
                    "rep" -> rep()
                    "steady" -> RepStep(duration(next()), percent(next()), kind = RepKind.STEADY)
                    "selfpaced" -> RepStep(duration(next()), 100, kind = RepKind.SELF_PACED)
                    "easy" -> EasyStep(duration(next()), percent(next()))
                    "gated" -> GatedStep
                    "ramp" -> ramp()
                    else -> throw LibraryParseException("unknown block '$word'")
                }
            )
        }

        private fun rep(): RepStep {
            val seconds = duration(next())
            val pct = percent(next())
            var cadence: Int? = null
            var best: Int? = null
            while (peek() == "cad" || peek() == "best") {
                when (next()) {
                    "cad" -> cadence = next().toIntOrNull()?.takeIf { it > 0 }
                        ?: throw LibraryParseException("cad needs a positive rpm")
                    "best" -> best = duration(next())
                }
            }
            if (best != null && best > seconds) throw LibraryParseException("best window longer than the rep")
            val kind = if (best != null) RepKind.SPRINT else RepKind.INTERVAL
            return RepStep(seconds, pct, cadence, best, kind)
        }

        private fun ramp(): RampStep {
            val start = Regex("(\\d+)W").matchEntire(next())?.groupValues?.get(1)?.toInt()
                ?: throw LibraryParseException("ramp needs a start like 100W")
            val step = Regex("\\+(\\d+)W/(\\d+[sm])").matchEntire(next())
                ?: throw LibraryParseException("ramp needs a step like +20W/1m")
            return RampStep(start, step.groupValues[1].toInt(), duration(step.groupValues[2]))
        }
    }

    private fun duration(token: String): Int {
        val m = Regex("(\\d+)([sm])").matchEntire(token)
            ?: throw LibraryParseException("expected a duration like 30s or 5m, got '$token'")
        val n = m.groupValues[1].toInt()
        if (n <= 0) throw LibraryParseException("duration must be positive")
        return if (m.groupValues[2] == "m") n * 60 else n
    }

    private fun percent(token: String): Int {
        val m = Regex("@(\\d+)").matchEntire(token)
            ?: throw LibraryParseException("expected a target like @110, got '$token'")
        return m.groupValues[1].toInt().takeIf { it > 0 }
            ?: throw LibraryParseException("target must be positive")
    }

    private fun percentRange(token: String): Pair<Int, Int> {
        val m = Regex("@(\\d+)(?:-(\\d+))?").matchEntire(token)
            ?: throw LibraryParseException("expected a target like @55 or @60-90, got '$token'")
        val from = m.groupValues[1].toInt()
        val to = m.groupValues[2].ifEmpty { m.groupValues[1] }.toInt()
        return from to to
    }
}
