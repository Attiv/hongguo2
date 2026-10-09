package e65

/** Small fixture for the verified CN 7.3.9.32 native selection contract. */
class w {
    enum class Resolution { High, SuperHigh, ExtremelyHigh }

    class Engine {
        var selected = Resolution.High
        var applications = 0

        fun configResolution(resolution: Resolution) {
            selected = resolution
            applications++
        }
    }

    var c = false
    val h = Engine()
    var fail = false

    fun r(resolution: Resolution) {
        h.configResolution(resolution)
        c = true
        if (fail) error("native selection failed")
    }
}
