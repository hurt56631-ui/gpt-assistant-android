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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
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

    // 两个核心发音人
    private static final String VOICE_BURMESE = "my-MM-NilarNeural";
    private static final String VOICE_CHINESE = "zh-CN-XiaoxiaoMultilingualNeural";

    private Context context;
    private TextToSpeech systemTts;
    private MediaPlayer mediaPlayer;
    private OkHttpClient client;
    private Handler mainHandler;

    // 播放队列相关
    private List<TextSegment> playQueue = new ArrayList<>();
    private int currentQueueIndex = -1;

    public TtsManager(Context context) {
        this.context = context;
        this.client = new OkHttpClient();
        this.mainHandler = new Handler(Looper.getMainLooper());

        systemTts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                systemTts.setLanguage(Locale.CHINA);
            }
        });
    }

    /**
     * 语段内部类，记录文本及其所属语言
     */
    private static class TextSegment {
        String text;
        boolean isBurmese;

        TextSegment(String text, boolean isBurmese) {
            this.text = text;
            this.isBurmese = isBurmese;
        }
    }

    public void speak(String rawText) {
        if (rawText == null || rawText.isEmpty()) return;

        // 1. 基础清理
        String textWithoutThink = removeThinkTags(rawText);
        String cleanText = cleanMarkdown(textWithoutThink);
        
        if (cleanText.isEmpty()) return;

        // 2. 停止当前播放并清空队列
        stop();

        // 3. 将混合文本切分成中缅交替的队列
        playQueue = splitTextByLanguage(cleanText);
        currentQueueIndex = 0;

        // 4. 开始播放第一段
        playNextInQueue();
    }

    /**
     * 核心逻辑：按语言属性切分字符串
     * 例如："你好 နေကောင်းလား 很高兴见到你" 
     * 会被切分为：[中文:"你好 "], [缅语:"နေကောင်းလား "], [中文:"很高兴见到你"]
     */
    private List<TextSegment> splitTextByLanguage(String text) {
        List<TextSegment> segments = new ArrayList<>();
        // 匹配缅文字符范围
        Pattern burmesePattern = Pattern.compile("[\\u1000-\\u109F]+");
        Matcher matcher = burmesePattern.matcher(text);

        int lastEnd = 0;
        while (matcher.find()) {
            // 如果缅文之前有其他文字（中文/英文/符号），先加入非缅语段
            if (matcher.start() > lastEnd) {
                String sub = text.substring(lastEnd, matcher.start()).trim();
                if (!sub.isEmpty()) {
                    segments.add(new TextSegment(sub, false));
                }
            }
            // 加入缅语段
            segments.add(new TextSegment(matcher.group(), true));
            lastEnd = matcher.end();
        }

        // 处理剩余的非缅语部分
        if (lastEnd < text.length()) {
            String sub = text.substring(lastEnd).trim();
            if (!sub.isEmpty()) {
                segments.add(new TextSegment(sub, false));
            }
        }
        return segments;
    }

    private void playNextInQueue() {
        if (currentQueueIndex >= 0 && currentQueueIndex < playQueue.size()) {
            TextSegment segment = playQueue.get(currentQueueIndex);
            
            boolean useCloud = GlobalDataHolder.getInstance(context).isEnableCloudTts();
            
            if (segment.isBurmese || useCloud) {
                String voiceId = segment.isBurmese ? VOICE_BURMESE : VOICE_CHINESE;
                playCloudTts(segment.text, voiceId);
            } else {
                playSystemTts(segment.text);
                // System TTS 需要通过 UtteranceProgressListener 监听结束，这里为简化直接跳下一段
                // 建议：如果系统TTS读很长的段落，需要增加回调
                currentQueueIndex++;
                playNextInQueue();
            }
        }
    }

    private String removeThinkTags(String text) {
        return text.replaceAll("(?s)<think>.*?</think>", "").trim();
    }

    private String cleanMarkdown(String text) {
        return text.replaceAll("```[\\s\\S]*?```", "")
                .replaceAll("[\\*\\#\\_\\>\\-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void playSystemTts(String text) {
        if (systemTts != null) {
            systemTts.speak(text, TextToSpeech.QUEUE_ADD, null, "segment_" + currentQueueIndex);
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
                mainHandler.post(() -> {
                    currentQueueIndex++;
                    playNextInQueue();
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    mainHandler.post(() -> {
                        currentQueueIndex++;
                        playNextInQueue();
                    });
                    return;
                }

                try (InputStream is = response.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(cacheFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, len);
                    }
                    fos.flush();
                    mainHandler.post(() -> playAudioFile(cacheFile));
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        currentQueueIndex++;
                        playNextInQueue();
                    });
                }
            }
        });
    }

    private void playAudioFile(File file) {
        try {
            if (mediaPlayer != null) {
                mediaPlayer.stop();
                mediaPlayer.reset();
                mediaPlayer.release();
            }
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(file.getAbsolutePath());
            
            // 关键点：当这一段播放完成，自动播放下一段
            mediaPlayer.setOnCompletionListener(mp -> {
                currentQueueIndex++;
                playNextInQueue();
            });

            mediaPlayer.setOnPreparedListener(MediaPlayer::start);
            mediaPlayer.prepareAsync();
        } catch (IOException e) {
            Log.e(TAG, "MediaPlayer Error", e);
            currentQueueIndex++;
            playNextInQueue();
        }
    }

    public void stop() {
        playQueue.clear();
        currentQueueIndex = -1;
        
        if (systemTts != null && systemTts.isSpeaking()) {
            systemTts.stop();
        }
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.reset();
            } catch (Exception e) {
                Log.e(TAG, "Stop MediaPlayer failed", e);
            }
        }
    }

    public void shutdown() {
        stop();
        if (systemTts != null) systemTts.shutdown();
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
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
