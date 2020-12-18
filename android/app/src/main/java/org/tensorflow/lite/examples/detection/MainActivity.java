package org.tensorflow.lite.examples.detection;

import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private TextToSpeech mTTS;
    private EditText editText;
    private SeekBar seekBar, seekBar1;
    private Button button;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        button = (Button) findViewById(R.id.button);

        Intent intent = getIntent();
        String beverageLocation = intent.getStringExtra("beverageLocation");

        editText.setText(beverageLocation);

        mTTS = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if(status == TextToSpeech.SUCCESS) {
                    int result = mTTS.setLanguage(Locale.KOREAN);

                    if(result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Toast.makeText(MainActivity.this, "지원하지 않는 언어 입니다.", Toast.LENGTH_SHORT).show();
                    } else {
                        button.setEnabled(true);
                    }
                } else {
                    Toast.makeText(MainActivity.this, "초기화 실패.", Toast.LENGTH_SHORT).show();
                }
            }
        });

        editText = (EditText) findViewById(R.id.editText);
        seekBar = (SeekBar) findViewById(R.id.seekBar);
        seekBar1 = (SeekBar) findViewById(R.id.seekBar1);

        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                speak();
            }
        });
    }

    private void speak() {
        String text = editText.getText().toString();
        float pitch = (float) seekBar.getProgress() / 50;
        if(pitch < 0.1) {
            pitch = 0.1f;
        }
        float speed = (float) seekBar1.getProgress() / 50;
        if(speed < 0.1) {
            speed = 0.1f;
        }
        Log.d("MainActivity", String.valueOf(speed));
        Log.d("MainActivity", String.valueOf(pitch));
        mTTS.setPitch(pitch);
        mTTS.setSpeechRate(speed);

        mTTS.speak(text, TextToSpeech.QUEUE_FLUSH, null);
    }

    @Override
    protected void onDestroy() {
        if(mTTS != null) {
            mTTS.stop();
            mTTS.shutdown();
        }
        super.onDestroy();
    }
}
