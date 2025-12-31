package com.skythinker.gptassistant;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;

public class GoogleAsrClient extends AsrClientBase {
    SpeechRecognizer speechRecognizer = null;
    IAsrCallback callback = null;
    Context context = null;
    boolean autoStop = false;
    
    // ★ 新增：存储设置的语言代码
    private String targetLanguage = null;

    public GoogleAsrClient(Context context) {
        this.context = context;

        if(!SpeechRecognizer.isRecognitionAvailable(context.getApplicationContext()))
            return;

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) { }
            @Override
            public void onBeginningOfSpeech() { }
            @Override
            public void onRmsChanged(float rmsdB) { }
            @Override
            public void onBufferReceived(byte[] buffer) { }
            @Override
            public void onEndOfSpeech() { }

            @Override
            public void onError(int error) {
                String errorStr = "Google ASR Error=" + error;
                if(error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
                    errorStr = "请在系统设置中允许Google应用的录音权限";
                else if(error == SpeechRecognizer.ERROR_NETWORK)
                    errorStr = "网络连接错误，请检查网络或代理";
                callback.onError(errorStr);
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> data = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if(data != null && !data.isEmpty()) {
                    callback.onResult(data.get(0));
                    if(autoStop) callback.onAutoStop();
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                ArrayList<String> data = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if(data != null && !data.isEmpty()) {
                    callback.onResult(data.get(0));
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) { }
        });
    }

    // ★ 关键重写：允许 MainActivity 动态设置语言
    @Override
    public void setLanguage(String langCode) {
        this.targetLanguage = langCode;
        Log.d("GoogleAsr", "Applied language: " + langCode);
    }

    @Override
    public void startRecognize() {
        if(speechRecognizer != null) {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            
            // ★ 修改：如果有手动设置的语言就用设置的，否则用系统默认
            if (targetLanguage != null && !targetLanguage.isEmpty()) {
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, targetLanguage);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, targetLanguage);
                intent.putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true);
            } else {
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            }

            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            
            try {
                speechRecognizer.startListening(intent);
            } catch (Exception e) {
                callback.onError("启动失败: " + e.getMessage());
            }
        } else {
            callback.onError("Google 识别组件不可用");
        }
    }

    @Override
    public void stopRecognize() {
        if(speechRecognizer != null) speechRecognizer.stopListening();
    }

    @Override
    public void cancelRecognize() {
        if(speechRecognizer != null) speechRecognizer.cancel();
    }

    @Override
    public void setCallback(IAsrCallback callback) {
        this.callback = callback;
    }

    @Override
    public void setParam(String key, Object value) { }

    @Override
    public void setEnableAutoStop(boolean enable) {
        autoStop = enable;
    }

    @Override
    public void destroy() {
        if(speechRecognizer != null) speechRecognizer.destroy();
    }
}
