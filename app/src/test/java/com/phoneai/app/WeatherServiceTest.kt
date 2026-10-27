package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherServiceTest {
    @Test fun recognizesNaturalWeatherQuestionsWithLowercasePlaceNames() {
        val request = "what the weather today in benin city Nigeria"
        assertTrue(WeatherService.isWeatherRequest(request))
        assertEquals("benin city Nigeria", WeatherService.requestedPlace(request))
    }

    @Test fun extractsPlaceWithOrWithoutPunctuationAndStopsBeforeTimeQualifier() {
        assertEquals("Benin City, Nigeria", WeatherService.requestedPlace("weather in Benin City, Nigeria today"))
        assertEquals("london", WeatherService.requestedPlace("forecast for london tomorrow"))
        assertEquals("", WeatherService.requestedPlace("weather today"))
        assertEquals("", WeatherService.requestedPlace("weather in my area today"))
    }

    @Test fun recognizesShortWeatherFollowUpTopicWithoutTreatingEveryWeatherMentionAsLive() {
        assertTrue(WeatherService.isWeatherTopic("weather"))
        assertTrue(WeatherService.isWeatherTopic("forecast"))
        assertFalse(WeatherService.isWeatherRequest("weather"))
        assertTrue(WeatherService.isWeatherRequest("weather today"))
        assertTrue(WeatherService.isWeatherRequest("will it rain tomorrow"))
    }
}
