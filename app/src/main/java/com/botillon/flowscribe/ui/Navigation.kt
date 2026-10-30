package com.botillon.flowscribe.ui

enum class PermKind { MICROPHONE, OVERLAY, ACCESSIBILITY, NOTIFICATIONS, BATTERY }

enum class Tab(val label: String) {
    HOME("Accueil"),
    KEYS("Clés"),
    PROMPTS("Styles"),
    DICTIONARY("Dico"),
    SETTINGS("Réglages"),
}
