package com.jrgames.blockpong;


import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.Window;

import java.util.prefs.Preferences;

/**
 * MainActivity is the main entry point to my game
 */
public class MainActivity extends AppCompatActivity {

    Game game;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d("MainActivity()", "onCreate()!!!!");
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT );

        // Immersive sticky fullscreen: system bars are hidden and only reappear temporarily on an
        // explicit edge swipe (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE), rather than the old
        // FLAG_FULLSCREEN's instant reveal on any touch near the top -- reduces (though doesn't
        // eliminate, see GameBoard.setTouchDeadZone()) the notification-shade swipe stealing an
        // in-progress aim near the top edge.
        Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        // The game draws its own header (level/score/best); the system action bar would just
        // eat screen space and visually collide with it.
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        // set content view to game so that objects of the game can be rendered to the screen
        SharedPreferences p = getPreferences(MODE_PRIVATE);
        game = new Game(this, p);

        setContentView(game);
    }

    @Override
    protected void onStop() {
        Log.d("MainActivity()", "onStop()");
        super.onStop();
    }

    @Override
    protected void onPause() {
        Log.d("MainActivity()", "onPause()");
        game.pause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        Log.d("MainActivity()", "onResume()");
        super.onResume();
        game.resume();
    }

}
