package co.touchlab.kampkit

import app.cash.turbine.test
import co.touchlab.kampkit.db.Breed
import co.touchlab.kampkit.mock.ClockMock
import co.touchlab.kampkit.mock.KtorApiMock
import co.touchlab.kampkit.models.BreedModel
import co.touchlab.kampkit.models.DataState
import co.touchlab.kampkit.models.ItemDataSummary
import co.touchlab.kermit.Kermit
import com.russhwolf.settings.MockSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Clock
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.hours

class BreedModelTest : BaseTest() {

    private var model: BreedModel = BreedModel()
    private var kermit = Kermit()
    private var dbHelper = DatabaseHelper(
        testDbConnection(),
        kermit,
        Dispatchers.Default
    )
    private val settings = MockSettings()
    private val ktorApi = KtorApiMock()

    // Need to start at non-zero time because the default value for db timestamp is 0
    private val clock = ClockMock(Clock.System.now())

    companion object {
        private val appenzeller = Breed(1, "appenzeller", 0L)
        private val australianNoLike = Breed(2, "australian", 0L)
        private val australianLike = Breed(2, "australian", 1L)
        val dataStateSuccessNoFavorite = DataState.Success(
            ItemDataSummary(appenzeller, listOf(appenzeller, australianNoLike))
        )
        private val dataStateSuccessFavorite = DataState.Success(
            ItemDataSummary(appenzeller, listOf(appenzeller, australianLike))
        )
    }

    @BeforeTest
    fun setup() {
        appStart(dbHelper, settings, ktorApi, kermit, clock)
    }

    @Test
    fun staleDataCheckTest() = runTest {
        val currentTimeMS = Clock.System.now().toEpochMilliseconds()
        settings.putLong(BreedModel.DB_TIMESTAMP_KEY, currentTimeMS)
        assertTrue(ktorApi.mock.getJsonFromApi.calledCount == 0)

        val expectedError = DataState.Error("Unable to download breed list")
        val actualError = model.getBreedsFromNetwork(0L)

        assertEquals(
            expectedError,
            actualError
        )
        assertTrue(ktorApi.mock.getJsonFromApi.calledCount == 0)
    }

    @ExperimentalTime
    @Test
    fun updateFavoriteTest() = runTest {
        ktorApi.mock.getJsonFromApi.returns(ktorApi.successResult())

        model.getBreeds().test {
            // Loading
            assertEquals(DataState.Loading, expectItem())
            // No Favorites
            assertEquals(dataStateSuccessNoFavorite, expectItem())
            // Add 1 favorite breed
            model.updateBreedFavorite(australianNoLike)
            // Get the new result with 1 breed favorited
            assertEquals(dataStateSuccessFavorite, expectItem())
        }
    }

    /* KG says to remove test until the native driver resets the in-memory db when the connection
       closes.

    @ExperimentalTime
    @Test
    fun fetchBreedsFromNetworkPreserveFavorites() {
        ktorApi.mock.getJsonFromApi.returns(ktorApi.successResult())

        runTest {
            model.getBreeds().test {
                // Loading
                assertEquals(DataState.Loading, expectItem())
                expectItem()
                model.updateBreedFavorite(australianNoLike)
                // Get the new result
                expectItem()
                cancel()
            }
        }

        runTest {
            model.getBreeds(true).test {
                // Loading
                assertEquals(DataState.Loading, expectItem())
                // Get the new result with 1 breed favorited
                val successFavoriteActual = expectItem() as DataState.Success
                kermit.d { "successFavoriteActual items: ${successFavoriteActual.data.allItems}" }
                assertEquals(dataStateSuccessFavorite, successFavoriteActual)
                cancel()
            }
        }
    }
    */

    @OptIn(ExperimentalTime::class)
    @Test
    fun updateDatabaseTest() = runTest {
        val successResult = ktorApi.successResult()
        ktorApi.mock.getJsonFromApi.returns(successResult)
        model.getBreeds().test {
            assertEquals(DataState.Loading, expectItem())
            val oldBreeds = expectItem()
            assertTrue(oldBreeds is DataState.Success)
            assertEquals(ktorApi.successResult().message.keys.size, oldBreeds.data.allItems.size)
        }

        // Advance time by more than an hour to make cached data stale
        clock.currentInstant += 2.hours
        val resultWithExtraBreed = successResult.copy().apply { message["extra"] = emptyList() }

        ktorApi.mock.getJsonFromApi.returns(resultWithExtraBreed)
        model.getBreeds().test {
            assertEquals(DataState.Loading, expectItem())
            val updated = expectItem()
            assertTrue(updated is DataState.Success)
            assertEquals(resultWithExtraBreed.message.keys.size, updated.data.allItems.size)
        }
    }

    @Test
    fun notifyErrorOnException() = runTest {
        ktorApi.mock.getJsonFromApi.throwOnCall(RuntimeException())
        assertNotNull(model.getBreedsFromNetwork(0L))
    }

    @ExperimentalTime
    @AfterTest
    fun breakdown() = runTest {
        dbHelper.deleteAll()
        // The in-memory SQLite native doesn't reset the autoincrement.
        // We need to destroy it and recreate it, but
        // it's not possible drop a table in SQLDelight:
        // https://github.com/cashapp/sqldelight/issues/1870
        /*        
        testDbConnection().execute(
            null,
            """
                DELETE FROM sqlite_sequence WHERE name = 'Breed';
            """.trimIndent(),
            0
        )
        */
        appEnd()
    }
}
