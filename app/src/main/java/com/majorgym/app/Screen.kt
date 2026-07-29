package com.majorgym.app

sealed class Screen {
    data object Dashboard : Screen()
    data object Members : Screen()
    data object Add : Screen()
    data class Edit(val id: String) : Screen()
    data class Profile(val id: String) : Screen()
    data class Renew(val id: String) : Screen()
    data object Backup : Screen()
}
