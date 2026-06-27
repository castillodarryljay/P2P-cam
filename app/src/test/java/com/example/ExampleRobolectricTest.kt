package com.example

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.ui.StreamAppUi
import com.example.ui.StreamViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun testStreamAppUiAllScreensAndQrScannerRendering() {
    val application = ApplicationProvider.getApplicationContext<Application>()
    
    // Grant runtime permissions in Robolectric
    shadowOf(application).grantPermissions(
      android.Manifest.permission.CAMERA,
      android.Manifest.permission.RECORD_AUDIO
    )
    
    val viewModel = StreamViewModel(application)
    
    composeTestRule.setContent {
      StreamAppUi(viewModel = viewModel)
    }
    composeTestRule.waitForIdle()

    // Navigate to Receiver Mode screen rendering
    viewModel.navigateTo(StreamViewModel.Screen.ReceiverMode)
    composeTestRule.waitForIdle()

    // Click the QR Code Scanner icon button to display QrScannerDialog
    composeTestRule.onNodeWithContentDescription("Scan QR Code").performClick()
    composeTestRule.waitForIdle()
  }
}
