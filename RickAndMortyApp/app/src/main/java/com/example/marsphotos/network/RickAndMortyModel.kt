package com.example.marsphotos.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val info: ApiInfo? = null,
    val results: List<T> = emptyList()
)

@Serializable
data class ApiInfo(
    val count: Int = 0,
    val pages: Int = 0,
    val next: String? = null,
    val prev: String? = null
)

@Serializable
data class CharacterModel(
    val id: Int = 0,
    val name: String = "",
    val status: String? = "",
    val species: String? = "",
    val type: String? = "",
    val gender: String? = "",
    val image: String? = ""
)

@Serializable
data class LocationModel(
    val id: Int = 0,
    val name: String = "",
    val type: String? = "",
    val dimension: String? = "",
    val residents: List<String> = emptyList()
)

@Serializable
data class EpisodeModel(
    val id: Int = 0,
    val name: String = "",
    @SerialName("air_date") val airDate: String? = "",
    val episode: String? = "",
    val characters: List<String> = emptyList()
)

sealed interface RickAndMortyItem {
    val id: Int
    val name: String
    val imageUrl: String?
    val field1Label: String
    val field1Value: String
    val field2Label: String
    val field2Value: String
    val categoryName: String

    data class Character(val character: CharacterModel) : RickAndMortyItem {
        override val id get() = character.id
        override val name get() = character.name
        override val imageUrl get() = character.image
        override val field1Label get() = "Status"
        override val field1Value get() = character.status.takeUnless { it.isNullBlinkingOrBlank() } ?: "Unknown"
        override val field2Label get() = "Species"
        override val field2Value get() = character.species.takeUnless { it.isNullBlinkingOrBlank() } ?: "Unknown"
        override val categoryName get() = "Character"
    }

    data class Location(val location: LocationModel) : RickAndMortyItem {
        override val id get() = location.id
        override val name get() = location.name
        override val imageUrl: String? get() = null
        override val field1Label get() = "Type"
        override val field1Value get() = location.type.takeUnless { it.isNullBlinkingOrBlank() } ?: "Unknown"
        override val field2Label get() = "Dimension"
        override val field2Value get() = location.dimension.takeUnless { it.isNullBlinkingOrBlank() } ?: "Unknown"
        override val categoryName get() = "Location"
    }

    data class Episode(val episode: EpisodeModel) : RickAndMortyItem {
        override val id get() = episode.id
        override val name get() = episode.name
        override val imageUrl: String? get() = null
        override val field1Label get() = "Air Date"
        override val field1Value get() = episode.airDate.takeUnless { it.isNullBlinkingOrBlank() } ?: "Unknown"
        override val field2Label get() = "Episode"
        override val field2Value get() = episode.episode.takeUnless { it.isNullBlinkingOrBlank() } ?: "Unknown"
        override val categoryName get() = "Episode"
    }
}

private fun String?.isNullBlinkingOrBlank(): Boolean = this == null || this.isBlank()
