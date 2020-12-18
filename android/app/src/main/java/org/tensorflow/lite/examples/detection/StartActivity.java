package org.tensorflow.lite.examples.detection;

import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;

public class StartActivity extends AppCompatActivity {
    private static final String TAG="StartActivity";

    private Button button;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start);

        button = (Button) findViewById(R.id.button);
        button.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                VoiceTask voiceTask=new VoiceTask();
                voiceTask.execute();
            }
        });
    }

    public class VoiceTask extends AsyncTask<String,Integer,String> {
        String str = null;

        @Override
        protected String doInBackground(String... params){
            //TODO Auto-generated method stub
            try {
                getVoice();
            } catch (Exception e){
                //TODO: handle exception
            }
            return str;
        }

        @Override
        protected  void onPostExecute(String result){
            try {

            } catch (Exception e){
                Log.d("onActivityResult","getImageURL exception");
            }
        }
    }

    private void getVoice(){
        Intent intent = new Intent();
        intent.setAction(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);

        String language="ko-KR"; // 영어: en-US  한국어: ko-KR

        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,language);
        startActivityForResult(intent,2);

    }

    protected  void onActivityResult(int requestCode, int resultCode,Intent data){
        //TODO Auto-generated method stub
        super.onActivityResult(requestCode,resultCode,data);

        if(resultCode==RESULT_OK){

            ArrayList<String> results=data
                    .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);

            String str=results.get(0);
            Toast.makeText(getBaseContext(), str, Toast.LENGTH_SHORT).show();

            gotoDetectorActivity(str);

        }
    }

    private void gotoDetectorActivity(String voice) {
        Intent intent = new Intent(this, DetectorActivity.class);
        intent.putExtra("voice", voice);
        startActivity(intent);
    }
}
