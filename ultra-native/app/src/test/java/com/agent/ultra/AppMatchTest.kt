package com.agent.ultra

import com.agent.ultra.agent.AppMatch
import com.agent.ultra.agent.AppMatch.App
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppMatchTest {
    private val apps = listOf(
        App("Notes", "com.samsung.android.app.notes"),
        App("Calculator", "com.sec.android.app.popupcalculator"),
        App("Maps", "com.google.android.apps.maps"),
        App("Play Store", "com.android.vending"),
        App("Clock", "com.sec.android.app.clockpackage"),
        App("My Files", "com.sec.android.app.myfiles"),
        App("Chrome", "com.android.chrome"),
        App("Google", "com.google.android.googlequicksearchbox"),
    )

    @Test fun saysMoreThanTheLabel() = assertEquals("com.samsung.android.app.notes", AppMatch.find("Samsung Notes", apps))
    @Test fun saysLessThanTheLabel() = assertEquals("com.sec.android.app.popupcalculator", AppMatch.find("calc", apps))
    @Test fun makerPlusLabel() = assertEquals("com.google.android.apps.maps", AppMatch.find("google maps", apps))
    @Test fun longestLabelWins() = assertEquals("com.android.vending", AppMatch.find("google play store", apps))
    @Test fun fillerIgnored() = assertEquals("com.sec.android.app.clockpackage", AppMatch.find("the clock app", apps))
    @Test fun exactPackage() = assertEquals("com.android.chrome", AppMatch.find("com.android.chrome", apps))
    @Test fun nothingInvented() = assertNull(AppMatch.find("whatsapp", apps))
    @Test fun multiWordLabel() = assertEquals("com.sec.android.app.myfiles", AppMatch.find("open my files", apps))

    @Test fun canonicalUsesTheUsersWords() =
        assertEquals("play store", AppMatch.canonical("open the play store", "Google Play Store", apps))
    @Test fun canonicalNeverSwapsTheApp() =
        assertNull(AppMatch.canonical("open the play store", "Chrome", apps))
    @Test fun canonicalWhenAlreadyTheirWords() =
        assertEquals("calculator", AppMatch.canonical("open the calculator", "calculator", apps))

    @Test fun googleMapsIsNotTheGoogleApp() = assertEquals("com.google.android.apps.maps", AppMatch.find("google maps", apps))
    @Test fun canonicalIsTheShortestName() {
        assertEquals("chrome", AppMatch.canonical("in chrome open my bookmarks", "Chrome", apps))
        assertEquals("maps", AppMatch.canonical("open google maps and search for coffee", "google maps", apps))
    }

    @Test fun featureWordFindsItsApp() {
        assertEquals("com.sec.android.app.clockpackage", AppMatch.find("Stopwatch", apps))
        assertEquals("com.sec.android.app.clockpackage", AppMatch.find("timer", apps))
        assertEquals("com.sec.android.app.myfiles", AppMatch.find("downloads", apps))
    }
    @Test fun aRealNameStillWinsOverAHint() =
        assertEquals("com.android.chrome", AppMatch.find("chrome", apps))

    // Both are labelled "Calendar"; only the package tells them apart (AndroidWorld, 2026-09-20).
    @Test fun twoAppsWithOneLabelAreToldApartByThePackage() {
        val apps = listOf(AppMatch.App("Calendar", "com.google.android.calendar"),
            AppMatch.App("Calendar", "com.simplemobiletools.calendar.pro"))
        assertEquals("com.simplemobiletools.calendar.pro", AppMatch.find("Simple Calendar Pro", apps))
        assertEquals("com.google.android.calendar", AppMatch.find("google calendar", apps))
        // Whatever words of the request it is rewritten to must still mean the same app — not "calendar".
        val mine = AppMatch.canonical("In Simple Calendar Pro, create a calendar event", "Simple Calendar Pro", apps)!!
        assertEquals("com.simplemobiletools.calendar.pro", AppMatch.find(mine, apps))
    }
}
