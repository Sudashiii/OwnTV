package dev.owntv.layout.time

private fun dateNow(): Double = js("Date.now()")

/** year,month,day,weekday (0 = Sunday),hour,minute,second in the browser's time zone. */
private fun localFields(): String =
    js("(() => { const d = new Date(); return [d.getFullYear(), d.getMonth() + 1, d.getDate(), d.getDay(), d.getHours(), d.getMinutes(), d.getSeconds()].join(','); })()")

actual object Clocks {
    actual fun nowMillis(): Long = dateNow().toLong()

    actual fun now(): WallClock {
        val f = localFields().split(',').map { it.toInt() }
        return WallClock(f[0], f[1], f[2], if (f[3] == 0) 7 else f[3], f[4], f[5], f[6])
    }

    actual fun format(pattern: String, time: WallClock): String? = TimePattern.format(pattern, time)

    actual fun patternError(pattern: String): String? = TimePattern.error(pattern)
}
