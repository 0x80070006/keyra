package com.example.app_clavier

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest{
    @Test fun launchesWithKeyraTitle(){
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            scenario.onActivity{activity->assertEquals("Keyra",activity.applicationInfo.loadLabel(activity.packageManager).toString())}
        }
    }
}
