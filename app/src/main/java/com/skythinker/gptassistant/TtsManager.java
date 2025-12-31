package com.skythinker.gptassistant;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Pattern;

import cn.hutool.json.JSONObject;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class TtsManager {
    private static final String TAG = "TtsManager";
    private static final String CLOUD_TTS_URL = "https://t.leftsite.cn/v1/audio/speech";
    
    private static final String VOICE_BURMESE = "my-MM-NilarNeural";
    private static final String VOICE_CHINESE = "zh-CN-XiaoxiaoNeural";
    
    private Context context;
    private TextToSpeech systemTts;
    private MediaPlayer mediaPlayer;
    private OkHttpClient client;
    private Handler mainHandler;

    public TtsManager(Context context) {
        this.context = context;
        this.client = new OkHttpClient();
        this.mainHandler = new Handler(Looper.getMainLooper());
        
        systemTts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                systemTts.setLanguage(Locale.getDefault());
            }
        });
    }

    public void speak(String rawText) {
        if (rawText == null || rawText.isEmpty()) return;

        String cleanText = removeThinkTags(rawText);
        if (cleanText.trim().isEmpty()) return;

        stop();

        boolean isBurmese = isBurmese(cleanText);
        boolean useCloud = GlobalDataHolder.getInstance(context).isEnableCloudTts();

        if (isBurmese || useCloud) {
            String voiceId = isBurmese ? VOICE_BURMESE : VOICE_CHINESE;
            playCloudTts(cleanText, voiceId);
        } else {
            playSystemTts(cleanText);
        }
    }

    private String removeThinkTags(String text) {
        return text.replaceAll("(?s)<think>.*?</think>", "").trim();
    }

    private boolean isBurmese(String text) {
        Pattern pattern = Pattern.compile(".*[\\u1000-\\u109F]+.*");
        return pattern.matcher(text).find();
    }

    private void playSystemTts(String text) {
        if (systemTts != null) {
            systemTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    private void playCloudTts(String text, String voiceId) {
        String fileName = md5(text + voiceId) + ".mp3";
        File cacheFile = new File(context.getExternalCacheDir(), fileName);

        if (cacheFile.exists()) {
            playAudioFile(cacheFile);
            return;
        }

        JSONObject jsonBody = new JSONObject();
        jsonBody.set("model", "tts-1");
        jsonBody.set("input", text);
        jsonBody.set("voice", voiceId);

        // ★★★ 修复：参数顺序调整 (MediaType, String) 适配 OkHttp 3/4 ★★★
        RequestBody body = RequestBody.create(
                MediaType.parse("application/json; charset=utf-8"),
                jsonBody.toString()
        );

        Request request = new Request.Builder()
                .url(CLOUD_TTS_URL)
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Cloud TTS Failed", e);
                mainHandler.post(() -> playSystemTts(text));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    mainHandler.post(() -> playSystemTts(text));
                    return;
                }

                try (InputStream is = response.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(cacheFile)) {
                    byte[] buffer = new byte[4096];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, len);
                    }
                    fos.flush();
                    
                    mainHandler.post(() -> playAudioFile(cacheFile));
                } catch (Exception e) {
                    Log.e(TAG, "Save Audio Failed", e);
                    mainHandler.post(() -> playSystemTts(text));
                }
            }
        });
    }

    private void playAudioFile(File file) {
        try {
            if (mediaPlayer != null) {
                mediaPlayer.release();
            }
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(file.getAbsolutePath());
            mediaPlayer.prepareAsync();
            mediaPlayer.setOnPreparedListener(MediaPlayer::start);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void stop() {
        if (systemTts != null && systemTts.isSpeaking()) {
            systemTts.stop();
        }
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.stop();
        }
    }

    public void shutdown() {
        if (systemTts != null) systemTts.shutdown();
        if (mediaPlayer != null) mediaPlayer.release();
    }

    private String md5(String s) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update(s.getBytes());
            byte[] messageDigest = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : messageDigest) {
                String h = Integer.toHexString(0xFF & b);
                while (h.length() < 2) h = "0" + h;
                hexString.append(h);
            }
            return hexString.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
