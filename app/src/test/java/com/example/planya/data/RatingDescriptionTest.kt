package com.example.planya.data

import com.example.planya.presentation.screens.profile.ratingDescription
import org.junit.Assert.*
import org.junit.Test

class RatingDescriptionTest {
    @Test fun descriptionsReflectSelectedScoreWithoutDefaultingToFive() {
        assertEquals("Elige tu valoración", ratingDescription(0))
        assertEquals("Muy mala experiencia", ratingDescription(1))
        assertEquals("Podría mejorar", ratingDescription(2))
        assertEquals("Buena experiencia", ratingDescription(3))
        assertEquals("Muy buena experiencia", ratingDescription(4))
        assertEquals("Excelente experiencia", ratingDescription(5))
    }
}
