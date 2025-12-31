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
    // 云端 TTS 接口地址
    private static final String CLOUD_TTS_URL = "https://t.leftsite.cn/v1/audio/speech";
    
    // 语音 ID 常量
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
        
        // 初始化系统 TTS 作为兜底方案
        systemTts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                systemTts.setLanguage(Locale.getDefault());
            }
        });
    }

    /**
     * 核心朗读方法
     * @param rawText 原始文本（可能包含 <think> 标签）
     */
    public void speak(String rawText) {
        if (rawText == null || rawText.isEmpty()) return;

        // 1. 过滤 DeepSeek 的 <think> 标签
        String cleanText = removeThinkTags(rawText);
        if (cleanText.trim().isEmpty()) return;

        // 停止上一次播放
        stop();

        // 2. 判断是否使用云端语音
        // 如果包含缅甸语，或者用户开启了云端 TTS，则使用云端
        boolean isBurmese = isBurmese(cleanText);
        boolean useCloud = GlobalDataHolder.getInstance(context).isEnableCloudTts();

        if (isBurmese || useCloud) {
            String voiceId = isBurmese ? VOICE_BURMESE : VOICE_CHINESE;
            playCloudTts(cleanText, voiceId);
        } else {
            playSystemTts(cleanText);
        }
    }

    // 移除 <think>...</think> 及其内容，支持跨行
    private String removeThinkTags(String text) {
        return text.replaceAll("(?s)<think>.*?</think>", "").trim();
    }

    // 检测是否包含缅甸语 Unicode 范围
    private boolean isBurmese(String text) {
        Pattern pattern = Pattern.compile(".*[\\u1000-\\u109F]+.*");
        return pattern.matcher(text).find();
    }

    // 使用系统 TTS 播放
    private void playSystemTts(String text) {
        if (systemTts != null) {
            systemTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    // 调用云端 API 播放
    private void playCloudTts(String text, String voiceId) {
        // 生成缓存文件名 (MD5)
        String fileName = md5(text + voiceId) + ".mp3";
        File cacheFile = new File(context.getExternalCacheDir(), fileName);

        // 如果缓存存在，直接播放文件
        if (cacheFile.exists()) {
            playAudioFile(cacheFile);
            return;
        }

        // 构建请求
        JSONObject jsonBody = new JSONObject();
        jsonBody.set("model", "tts-1");
        jsonBody.set("input", text);
        jsonBody.set("voice", voiceId);

        RequestBody body = RequestBody.create(
                jsonBody.toString(), 
                MediaType.parse("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(CLOUD_TTS_URL)
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Cloud TTS Failed", e);
                // 失败降级到系统 TTS
                mainHandler.post(() -> playSystemTts(text));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    mainHandler.post(() -> playSystemTts(text));
                    return;
                }

                // 保存音频流到文件
                try (InputStream is = response.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(cacheFile)) {
                    byte[] buffer = new byte[4096];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, len);
                    }
                    fos.flush();
                    
                    // 播放下载好的文件
                    mainHandler.post(() -> playAudioFile(cacheFile));
                } catch (Exception e) {
                    Log.e(TAG, "Save Audio Failed", e);
                    mainHandler.post(() -> playSystemTts(text));
                }
            }
        });
    }

    // 播放本地音频文件
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
