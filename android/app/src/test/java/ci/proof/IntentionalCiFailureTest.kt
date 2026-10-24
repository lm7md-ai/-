package ci.proof

import android.app.Application
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IntentionalCiFailureTest {
    @Test fun pipelineMustRejectAFailingTest() {
        fail("Intentional CI verification failure; this test is removed before delivery")
    }
}
