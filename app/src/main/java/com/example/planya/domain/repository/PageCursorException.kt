package com.example.planya.domain.repository

class PageCursorException : IllegalStateException("La continuación venció o ya no es válida. Actualiza la lista para seguir.")
