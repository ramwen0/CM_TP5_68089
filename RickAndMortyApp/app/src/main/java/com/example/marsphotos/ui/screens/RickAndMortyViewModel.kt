package com.example.marsphotos.ui.screens

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.marsphotos.network.RickAndMortyApi
import com.example.marsphotos.network.RickAndMortyItem
import com.example.marsphotos.network.CharacterModel
import com.example.marsphotos.network.LocationModel
import com.example.marsphotos.network.EpisodeModel
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.FirebaseDatabase
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException
import kotlin.time.Duration.Companion.milliseconds

enum class Category(val displayName: String) {
    CHARACTER("Character"),
    EPISODE("Episode"),
    LOCATION("Location")
}

data class SavedItem(
    val id: Int = 0,
    val name: String = "",
    val category: String = "",
    val imageUrl: String? = null,
    val field1Label: String = "",
    val field1Value: String = "",
    val field2Label: String = "",
    val field2Value: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

data class LocationPhoto(
    val locationId: Int = 0,
    val locationName: String = "",
    val filePath: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis()
)

sealed interface RickAndMortyUiState {
    object Loading : RickAndMortyUiState
    object Error : RickAndMortyUiState
    object Empty : RickAndMortyUiState
    data class Success(
        val totalCount: Int,
        val items: List<RickAndMortyItem>,
        val selectedItem: RickAndMortyItem
    ) : RickAndMortyUiState
}

class RickAndMortyViewModel : ViewModel() {
    var uiState: RickAndMortyUiState by mutableStateOf(RickAndMortyUiState.Loading)
        private set

    var selectedCategory: Category by mutableStateOf(Category.CHARACTER)
        private set

    var filterQuery: String by mutableStateOf("")
        private set

    var rollCount by mutableStateOf(0)
        private set

    var saveMessage by mutableStateOf<String?>(null)
        private set

    var savedHistory by mutableStateOf<List<SavedItem>>(emptyList())
        private set

    var locationPhotos by mutableStateOf<Map<Int, LocationPhoto>>(emptyMap())
        private set

    var photoTarget by mutableStateOf<RickAndMortyItem.Location?>(null)
        private set

    private var fetchJob: Job? = null

    private val database = try {
        FirebaseDatabase.getInstance().reference
    } catch (e: Exception) {
        Log.e("RickAndMortyVM", "Firebase not available", e)
        null
    }

    init {
        fetchData()
        observeFirebaseData()
    }

    private fun observeFirebaseData() {
        database?.let { db ->
            db.child("roll_count").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val count = snapshot.getValue(Long::class.java)?.toInt() ?: 0
                    rollCount = count
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e("RickAndMortyVM", "Roll count read cancelled", error.toException())
                }
            })

            db.child("saved_history").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val list = mutableListOf<SavedItem>()
                    for (child in snapshot.children) {
                        child.getValue(SavedItem::class.java)?.let { list.add(it) }
                    }
                    savedHistory = list.reversed() // Most recent first
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e("RickAndMortyVM", "Saved history read cancelled", error.toException())
                }
            })

            db.child("location_photos").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val photos = mutableMapOf<Int, LocationPhoto>()
                    for (child in snapshot.children) {
                        child.getValue(LocationPhoto::class.java)?.let { photos[it.locationId] = it }
                    }
                    locationPhotos = photos
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e("RickAndMortyVM", "Location photos read cancelled", error.toException())
                }
            })
        }
    }

    fun onCategorySelected(category: Category) {
        if (selectedCategory != category) {
            selectedCategory = category
            fetchData()
        }
    }

    fun onQueryChanged(query: String) {
        filterQuery = query
        fetchData(debounceMs = 300)
    }

    fun roll() {
        val currentState = uiState
        if (currentState is RickAndMortyUiState.Success && currentState.items.isNotEmpty()) {
            val newItem = currentState.items.random()
            uiState = currentState.copy(selectedItem = newItem)

            database?.let { db ->
                db.child("roll_count").get().addOnSuccessListener { snapshot ->
                    val current = snapshot.getValue(Long::class.java)?.toInt() ?: 0
                    db.child("roll_count").setValue(current + 1)
                }.addOnFailureListener {
                    db.child("roll_count").setValue(rollCount + 1)
                }
            } ?: run {
                rollCount++
            }
        }
    }

    fun saveCurrentItem() {
        val currentState = uiState
        if (currentState is RickAndMortyUiState.Success) {
            val item = currentState.selectedItem
            val savedItem = SavedItem(
                id = item.id,
                name = item.name,
                category = item.categoryName,
                imageUrl = item.imageUrl,
                field1Label = item.field1Label,
                field1Value = item.field1Value,
                field2Label = item.field2Label,
                field2Value = item.field2Value,
                timestamp = System.currentTimeMillis()
            )

            database?.let { db ->
                db.child("latest_saved").setValue(savedItem)
                db.child("saved_history").push().setValue(savedItem)
                saveMessage = "Saved '${item.name}' to Firebase!"
            } ?: run {
                saveMessage = "Firebase not initialized (missing google-services.json)"
            }
        }
    }

    fun loadLastSavedItem() {
        database?.let { db ->
            db.child("latest_saved").get().addOnSuccessListener { snapshot ->
                val saved = snapshot.getValue(SavedItem::class.java)
                if (saved != null) {
                    val item = when (saved.category.lowercase()) {
                        "character" -> RickAndMortyItem.Character(
                            CharacterModel(
                                id = saved.id,
                                name = saved.name,
                                status = if (saved.field1Label == "Status") saved.field1Value else null,
                                species = if (saved.field2Label == "Species") saved.field2Value else null,
                                image = saved.imageUrl
                            )
                        )
                        "location" -> RickAndMortyItem.Location(
                            LocationModel(
                                id = saved.id,
                                name = saved.name,
                                type = if (saved.field1Label == "Type") saved.field1Value else null,
                                dimension = if (saved.field2Label == "Dimension") saved.field2Value else null
                            )
                        )
                        else -> RickAndMortyItem.Episode(
                            EpisodeModel(
                                id = saved.id,
                                name = saved.name,
                                airDate = if (saved.field1Label == "Air Date") saved.field1Value else null,
                                episode = if (saved.field2Label == "Episode") saved.field2Value else null
                            )
                        )
                    }

                    selectedCategory = when (saved.category.lowercase()) {
                        "character" -> Category.CHARACTER
                        "location" -> Category.LOCATION
                        else -> Category.EPISODE
                    }

                    // Fetch full list of items for this category so rolling works
                    viewModelScope.launch {
                        try {
                            val (items, count) = when (selectedCategory) {
                                Category.CHARACTER -> {
                                    val response = RickAndMortyApi.retrofitService.getCharacters(null)
                                    val itemList = response.results.map { RickAndMortyItem.Character(it) }
                                    itemList to (response.info?.count ?: itemList.size)
                                }
                                Category.LOCATION -> {
                                    val response = RickAndMortyApi.retrofitService.getLocations(null)
                                    val itemList = response.results.map { RickAndMortyItem.Location(it) }
                                    itemList to (response.info?.count ?: itemList.size)
                                }
                                Category.EPISODE -> {
                                    val response = RickAndMortyApi.retrofitService.getEpisodes(null)
                                    val itemList = response.results.map { RickAndMortyItem.Episode(it) }
                                    itemList to (response.info?.count ?: itemList.size)
                                }
                            }

                            uiState = RickAndMortyUiState.Success(
                                totalCount = count,
                                items = items,
                                selectedItem = item
                            )
                            saveMessage = "Loaded last saved item: ${saved.name}"
                        } catch (e: Exception) {
                            Log.e("RickAndMortyVM", "Error fetching category items on load", e)
                            uiState = RickAndMortyUiState.Success(
                                totalCount = 1,
                                items = listOf(item),
                                selectedItem = item
                            )
                            saveMessage = "Loaded last saved item: ${saved.name}"
                        }
                    }
                } else {
                    saveMessage = "No last saved item found in Firebase."
                }
            }.addOnFailureListener {
                saveMessage = "Failed to load last saved item."
            }
        } ?: run {
            saveMessage = "Firebase not initialized."
        }
    }

    fun openCamera(location: RickAndMortyItem.Location) {
        photoTarget = location
    }

    fun closeCamera() {
        photoTarget = null
    }

    fun saveLocationPhoto(filePath: String, latitude: Double, longitude: Double) {
        val target = photoTarget ?: return
        val photo = LocationPhoto(
            locationId = target.id,
            locationName = target.name,
            filePath = filePath,
            latitude = latitude,
            longitude = longitude,
            timestamp = System.currentTimeMillis()
        )

        locationPhotos[target.id]?.let { previous ->
            if (previous.filePath != filePath) File(previous.filePath).delete()
        }
        locationPhotos = locationPhotos + (target.id to photo)
        database?.child("location_photos")?.child(target.id.toString())?.setValue(photo)

        saveMessage = "Photo saved for '${target.name}'"
        photoTarget = null
    }

    fun clearSaveMessage() {
        saveMessage = null
    }

    fun fetchData(debounceMs: Long = 0) {
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            if (debounceMs > 0) {
                delay(debounceMs.milliseconds)
            }
            uiState = RickAndMortyUiState.Loading
            val queryParam = if (filterQuery.isBlank()) null else filterQuery.trim()
            try {
                val (items, count) = when (selectedCategory) {
                    Category.CHARACTER -> {
                        val response = RickAndMortyApi.retrofitService.getCharacters(queryParam)
                        val itemList = response.results.map { RickAndMortyItem.Character(it) }
                        itemList to (response.info?.count ?: itemList.size)
                    }
                    Category.LOCATION -> {
                        val response = RickAndMortyApi.retrofitService.getLocations(queryParam)
                        val itemList = response.results.map { RickAndMortyItem.Location(it) }
                        itemList to (response.info?.count ?: itemList.size)
                    }
                    Category.EPISODE -> {
                        val response = RickAndMortyApi.retrofitService.getEpisodes(queryParam)
                        val itemList = response.results.map { RickAndMortyItem.Episode(it) }
                        itemList to (response.info?.count ?: itemList.size)
                    }
                }

                if (items.isEmpty()) {
                    uiState = RickAndMortyUiState.Empty
                } else {
                    uiState = RickAndMortyUiState.Success(
                        totalCount = count,
                        items = items,
                        selectedItem = items.random()
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                Log.e("RickAndMortyVM", "HttpException code=${e.code()} message=${e.message()}", e)
                if (e.code() == 404) {
                    uiState = RickAndMortyUiState.Empty
                } else {
                    uiState = RickAndMortyUiState.Error
                }
            } catch (e: Exception) {
                Log.e("RickAndMortyVM", "Error fetching data from API", e)
                uiState = RickAndMortyUiState.Error
            }
        }
    }
}
