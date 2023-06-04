package com.raideone.microscore

object Constants {
    val SPINNER_ITEMS = listOf(
        SpinnerItem("0%", 0),
        SpinnerItem("20%", 20),
        SpinnerItem("40%", 40),
        SpinnerItem("50%", 50),
        SpinnerItem("60%", 60),
        SpinnerItem("70%", 70),
        SpinnerItem("80%", 80),
        SpinnerItem("90%", 90),
        SpinnerItem("100%", 100),
        SpinnerItem("110%", 110),
        SpinnerItem("120%", 120),
        SpinnerItem("130%", 130),
        SpinnerItem("140%", 140),
        SpinnerItem("150%", 150),
        SpinnerItem("170%", 170),
        SpinnerItem("190%", 190),
        SpinnerItem("200%", 200),
        SpinnerItem("210%", 210),
        SpinnerItem("220%", 220),
        SpinnerItem("230%", 230),
        SpinnerItem("240%", 240),
        SpinnerItem("250%", 250),
        SpinnerItem("260%", 260),
        SpinnerItem("270%", 270),
        SpinnerItem("280%", 280),
        SpinnerItem("290%", 290),
        SpinnerItem("300%", 300),
        SpinnerItem("320%", 320),
        SpinnerItem("340%", 340)
    )

    const val PREF_BOSS = "boss_widget_prefs"
    const val PREF_GOLEM = "golem_widget_prefs"
    const val PREF_SERVICE_STATE = "service_state"

    const val KEY_POS_X = "widget_x"
    const val KEY_POS_Y = "widget_y"
    const val KEY_SPINNER_VALUE = "spinnerValue"
}

data class SpinnerItem(val displayText: String, val value: Int) {
    override fun toString(): String {
        return displayText
    }
}
