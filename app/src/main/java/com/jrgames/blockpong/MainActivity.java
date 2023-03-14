package com.jrgames.blockpong;


import androidx.appcompat.app.AppCompatActivity;

import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

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

        // set window to full screen
        Window window=getWindow();
        window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

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

}
