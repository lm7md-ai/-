package sa.rihla.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import org.robolectric.RuntimeEnvironment

/**
 * AndroidViewModelFactory.getInstance caches an Application across Robolectric methods. Use its
 * public constructor so every screen receives this test's Application and database.
 */
@Composable
fun FreshTestApplication(content: @Composable () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val application = RuntimeEnvironment.getApplication()
    val owner =
        remember(activity, application) {
            object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
                override val viewModelStore = activity.viewModelStore
                override val defaultViewModelProviderFactory =
                    ViewModelProvider.AndroidViewModelFactory(application)
                override val defaultViewModelCreationExtras =
                    activity.defaultViewModelCreationExtras
            }
        }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}
