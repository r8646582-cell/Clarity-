package com.umair.purpose.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SecretStoreTest {
    @Test fun `an unreadable saved database key is never replaced`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("purpose_secrets", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("db_passphrase", "damaged").commit()
        assertThrows(IllegalStateException::class.java) { SecretStore(context).dbPassphrase() }
        assertEquals("damaged", prefs.getString("db_passphrase", null))
        prefs.edit().clear().commit()
    }
}
