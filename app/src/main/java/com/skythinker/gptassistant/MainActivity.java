package com.skythinker.gptassistant;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.PaintDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ImageSpan;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONException;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import io.noties.prism4j.annotations.PrismBundle;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import com.skythinker.gptassistant.ChatManager.ChatMessage.ChatRole;
import com.skythinker.gptassistant.ChatManager.ChatMessage;
import com.skythinker.gptassistant.ChatManager.MessageList;
import com.skythinker.gptassistant.ChatManager.Conversation;

@SuppressLint({"UseCompatLoadingForDrawables", "JavascriptInterface", "SetTextI18n"})
@PrismBundle(includeAll = true)
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";

    private int selectedTab = 0;
    private TextView tvGptReply;
    private EditText etUserInput;
    private ImageButton btSend, btAttachment;
    private ScrollView svChatArea;
    private LinearLayout llChatList;
    private PopupWindow pwMenu;
    private Handler handler;
    private MarkdownRenderer markdownRenderer;
    private long asrStartTime = 0;
    BroadcastReceiver localReceiver = null;

    private static boolean isAlive = false;
    private static boolean isRunning = false;

    // ★★★ 移除 ChatApiClient，使用 OkHttp 替代 ★★★
    private OkHttpClient okHttpClient;
    private Call currentCall;
    private String chatApiBuffer = "";

    // ★★★ TTS 替换：使用 TtsManager 替代 TextToSpeech ★★★
    private TtsManager ttsManager;
    private boolean ttsEnabled = true;
    final private List<String> ttsSentenceSeparator = Arrays.asList("。", ".", "？", "?", "！", "!", "……", "\n", "：", ":");
    private int ttsSentenceEndIndex = 0;

    private boolean multiChat = false;
    ChatManager chatManager = null;
    private Conversation currentConversation = null; 
    private MessageList multiChatList = null; 

    private boolean multiVoice = false;

    private JSONObject currentTemplateParams = null; 

    AsrClientBase asrClient = null;
    AsrClientBase.IAsrCallback asrCallback = null;

    WebScraper webScraper = null;

    Uri photoUri = null;

    ArrayList<ChatMessage.Attachment> selectedAttachments = new ArrayList<>(); 

    DocumentParser documentParser = null;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() { 
            @Override
            public void uncaughtException(@NonNull Thread thread, @NonNull Throwable throwable) {
                Log.e("UncaughtException", thread.getClass().getName() + " " + throwable.getMessage());
                throwable.printStackTrace();
                System.exit(-1);
            }
        });

        handler = new Handler(Looper.getMainLooper()); 

        GlobalDataHolder.init(this); 

        markdownRenderer = new MarkdownRenderer(this);

        // ★★★ 初始化新的 TTS 管理器 ★★★
        ttsManager = new TtsManager(this);

        // ★★★ 初始化 OkHttp ★★★
        okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS) 
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();

        setContentView(R.layout.activity_main); 
        overridePendingTransition(R.anim.translate_up_in, R.anim.translate_down_out); 
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS); 
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(Color.TRANSPARENT);

        tvGptReply = findViewById(R.id.tv_chat_notice);
        tvGptReply.setTextIsSelectable(true);
        tvGptReply.setMovementMethod(LinkMovementMethod.getInstance());
        etUserInput = findViewById(R.id.et_user_input);
        btSend = findViewById(R.id.bt_send);
        btAttachment = findViewById(R.id.bt_attachment);
        svChatArea = findViewById(R.id.sv_chat_list);
        llChatList = findViewById(R.id.ll_chat_list);

        documentParser = new DocumentParser(this); 
        handleShareIntent(getIntent()); 

        updateForMultiWindowMode(); 

        findViewById(R.id.ll_main).setOnDragListener((v, event) -> { 
            if(event.getAction() == DragEvent.ACTION_DROP) {
                requestDragAndDropPermissions(event);
                ClipData clipData = event.getClipData();
                if(clipData != null) {
                    for (int i = 0; i < clipData.getItemCount(); i++) {
                        ClipData.Item item = clipData.getItemAt(i);
                        Uri uri = item.getUri();
                        if (uri != null) { 
                            addAttachment(uri);
                        } else { 
                            if(item.getText() != null) {
                                String text = item.getText().toString();
                                String inputText = etUserInput.getText().toString();
                                if(!inputText.equals("")) {
                                    etUserInput.setText(inputText + "\n\n" + text);
                                } else {
                                    etUserInput.setText(text);
                                }
                            }
                        }
                    }
                }
            }
            return true;
        });

        chatManager = new ChatManager(this); 
        ChatMessage.setContext(this); 

        webScraper = new WebScraper(this, findViewById(R.id.ll_main_base)); 

        // ★★★ 核心修改：初始化输入监听和动态按钮 ★★★
        setupInputListener();

        // 附件选择按钮点击事件
        btAttachment.setOnClickListener(view -> {
            PopupWindow popupWindow = getAttachmentPopupWindow();
            popupWindow.showAtLocation(btAttachment, Gravity.BOTTOM | Gravity.START, dpToPx(3), dpToPx(43));
            View container = popupWindow.getContentView().getRootView();
            Context context = popupWindow.getContentView().getContext();
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) container.getLayoutParams();
            params.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            params.dimAmount = 0.3f;
            wm.updateViewLayout(container, params);
        });

        // 长按输入框开始录音或清空内容
        etUserInput.setOnLongClickListener(view -> {
            if(etUserInput.getText().toString().equals("")) {
                Intent broadcastIntent = new Intent("com.skythinker.gptassistant.KEY_SPEECH_START");
                LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent);
                view.setTag("recording");
            } else {
                etUserInput.setText("");
            }
            return true;
        });

        etUserInput.setOnTouchListener((view, motionEvent) -> {
            if(motionEvent.getAction() == MotionEvent.ACTION_UP){
                if("recording".equals(view.getTag())){
                    Intent broadcastIntent = new Intent("com.skythinker.gptassistant.KEY_SPEECH_STOP");
                    LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent);
                    view.setTag(null);
                }
            }
            return false;
        });

        // 连续对话按钮点击事件
        (findViewById(R.id.cv_multi_chat)).setOnClickListener(view -> {
            multiChat = !multiChat;
            if(multiChat){
                ((CardView) findViewById(R.id.cv_multi_chat)).setForeground(getDrawable(R.drawable.chat_btn_enabled));
                GlobalUtils.showToast(this, R.string.toast_multi_chat_on, false);
            }else{
                ((CardView) findViewById(R.id.cv_multi_chat)).setForeground(getDrawable(R.drawable.chat_btn));
                GlobalUtils.showToast(this, R.string.toast_multi_chat_off, false);
            }
        });

        // 新建对话按钮点击事件
        (findViewById(R.id.cv_new_chat)).setOnClickListener(view -> {
            clearChatListView();

            if(currentConversation != null &&
                    ((multiChatList.size() > 0 && multiChatList.get(0).role != ChatRole.SYSTEM) || (multiChatList.size() > 1 && multiChatList.get(0).role == ChatRole.SYSTEM)) &&
                    GlobalDataHolder.getAutoSaveHistory()) 
                chatManager.addConversation(currentConversation);

            currentConversation = new Conversation();
            multiChatList = currentConversation.messages;
        });

        View menuView = LayoutInflater.from(this).inflate(R.layout.main_popup_menu, null);
        pwMenu = new PopupWindow(menuView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pwMenu.setOutsideTouchable(true);

        (findViewById(R.id.cv_new_chat)).performClick(); 

        // TTS开关按钮点击事件
        (findViewById(R.id.cv_tts_off)).setOnClickListener(view -> {
            ttsEnabled = !ttsEnabled;
            if(ttsEnabled) {
                ((CardView) findViewById(R.id.cv_tts_off)).setForeground(getDrawable(R.drawable.tts_off));
                GlobalUtils.showToast(this, R.string.toast_tts_on, false);
            }else{
                ((CardView) findViewById(R.id.cv_tts_off)).setForeground(getDrawable(R.drawable.tts_off_enable));
                GlobalUtils.showToast(this, R.string.toast_tts_off, false);
                ttsManager.stop();
            }
        });

        // 连续语音对话按钮点击事件
        (findViewById(R.id.cv_voice_chat)).setOnClickListener(view -> {
            if(!multiVoice && !ttsEnabled) { 
                GlobalUtils.showToast(this, R.string.toast_voice_chat_tts_off, false);
                return;
            }
            multiVoice = !multiVoice;
            if(multiVoice){
                ((CardView) findViewById(R.id.cv_voice_chat)).setForeground(getDrawable(R.drawable.voice_chat_btn_enabled));
                asrClient.setEnableAutoStop(true);
                Intent intent = new Intent("com.skythinker.gptassistant.KEY_SPEECH_START");
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
                GlobalUtils.showToast(this, R.string.toast_multi_voice_on, false);
            } else {
                ((CardView) findViewById(R.id.cv_voice_chat)).setForeground(getDrawable(R.drawable.voice_chat_btn));
                asrClient.setEnableAutoStop(false);
                Intent intent = new Intent("com.skythinker.gptassistant.KEY_SPEECH_STOP");
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
                GlobalUtils.showToast(this, R.string.toast_multi_voice_off, false);
            }
        });

        // 历史按钮点击事件
        (menuView.findViewById(R.id.cv_history)).setOnClickListener(view -> {
            pwMenu.dismiss();
            Intent intent = new Intent(MainActivity.this, HistoryActivity.class);
            startActivityForResult(intent, 3);
        });

        // 设置按钮点击事件
        (menuView.findViewById(R.id.cv_settings)).setOnClickListener(view -> {
            pwMenu.dismiss();
            startActivityForResult(new Intent(MainActivity.this, TabConfActivity.class), 0);
        });

        // 关闭按钮点击事件
        (menuView.findViewById(R.id.cv_close)).setOnClickListener(view -> {
            finish();
        });

        // 更多按钮点击事件
        (findViewById(R.id.cv_more)).setOnClickListener(view -> {
            pwMenu.showAsDropDown(view, 0, 0);
        });

        // 上方空白区域点击事件
        (findViewById(R.id.view_bg_empty)).setOnClickListener(view -> {
            finish();
        });

        // 用户设置为启动时开启连续对话
        if(GlobalDataHolder.getDefaultEnableMultiChat()){
            multiChat = true;
            ((CardView) findViewById(R.id.cv_multi_chat)).setForeground(getDrawable(R.drawable.chat_btn_enabled));
        }

        // 用户设置为启动时开启TTS
        if(!GlobalDataHolder.getDefaultEnableTts()){
            ttsEnabled = false;
            ((CardView) findViewById(R.id.cv_tts_off)).setForeground(getDrawable(R.drawable.tts_off_enable));
        }

        // 处理选中的模板
        if(GlobalDataHolder.getSelectedTab() != -1 && GlobalDataHolder.getSelectedTab() < GlobalDataHolder.getTabDataList().size())
            selectedTab = GlobalDataHolder.getSelectedTab();
        switchToTemplate(selectedTab);
        Button selectedTabBtn = (Button) ((LinearLayout) findViewById(R.id.tabs_layout)).getChildAt(selectedTab); 
        selectedTabBtn.getParent().requestChildFocus(selectedTabBtn, selectedTabBtn);

        updateModelSpinner(); 

        isAlive = true; 

        requestPermission(); 

        // 初始化语音识别回调
        asrCallback = new AsrClientBase.IAsrCallback() {
            @Override
            public void onError(String msg) {
                if(tvGptReply != null) {
                    runOnUiThread(() -> tvGptReply.setText(getString(R.string.text_asr_error_prefix) + msg));
                }else{
                    Toast.makeText(MainActivity.this, getString(R.string.text_asr_error_prefix) + msg, Toast.LENGTH_LONG).show();
                }
                if(multiVoice) {
                    (findViewById(R.id.cv_voice_chat)).performClick();
                }
            }

            @Override
            public void onResult(String result) {
                if(result != null) {
                    runOnUiThread(() -> etUserInput.setText(result));
                }
            }

            @Override
            public void onAutoStop() {
                if(multiVoice) {
                    Intent broadcastIntent = new Intent("com.skythinker.gptassistant.KEY_SPEECH_STOP");
                    LocalBroadcastManager.getInstance(MainActivity.this).sendBroadcast(broadcastIntent);
                    Intent broadcastIntent2 = new Intent("com.skythinker.gptassistant.KEY_SEND");
                    LocalBroadcastManager.getInstance(MainActivity.this).sendBroadcast(broadcastIntent2);
                }
            }
        };
        // 设置使用百度/Whisper/华为语音识别
        if(GlobalDataHolder.getAsrUseBaidu()) {
            setAsrClient("baidu");
        } else if(GlobalDataHolder.getAsrUseWhisper()) {
            setAsrClient("whisper");
        } else if(GlobalDataHolder.getAsrUseGoogle()) {
            setAsrClient("google");
        } else {
            setAsrClient("hms");
        }

        // 设置本地广播接收器
        localReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if(action.equals("com.skythinker.gptassistant.KEY_SPEECH_START")) { 
                    ttsManager.stop();
                    asrClient.startRecognize();
                    asrStartTime = System.currentTimeMillis();
                    etUserInput.setText("");
                    etUserInput.setHint(R.string.text_listening_hint);
                } else if(action.equals("com.skythinker.gptassistant.KEY_SPEECH_STOP")) { 
                    etUserInput.setHint(R.string.text_input_hint);
                    if(System.currentTimeMillis() - asrStartTime < 1000) {
                        asrClient.cancelRecognize();
                    } else {
                        asrClient.stopRecognize();
                    }
                } else if(action.equals("com.skythinker.gptassistant.KEY_SEND")) { 
                    sendQuestion(null);
                } else if(action.equals("com.skythinker.gptassistant.SHOW_KEYBOARD")) { 
                    etUserInput.requestFocus();
                    InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    imm.showSoftInput(findViewById(R.id.et_user_input), InputMethodManager.RESULT_UNCHANGED_SHOWN);
                }
            }
        };
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("com.skythinker.gptassistant.KEY_SPEECH_START");
        intentFilter.addAction("com.skythinker.gptassistant.KEY_SPEECH_STOP");
        intentFilter.addAction("com.skythinker.gptassistant.KEY_SEND");
        intentFilter.addAction("com.skythinker.gptassistant.SHOW_KEYBOARD");
        LocalBroadcastManager.getInstance(this).registerReceiver(localReceiver, intentFilter);

        // 检查无障碍权限
        if(GlobalDataHolder.getCheckAccessOnStart()) {
            if(!MyAccessbilityService.isConnected()) { 
                new ConfirmDialog(this)
                    .setContent(getString(R.string.text_access_notice))
                    .setOnConfirmListener(() -> {
                        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                        startActivity(intent);
                    })
                    .setOnCancelListener(() -> {
                        Toast.makeText(MainActivity.this, getString(R.string.toast_access_error), Toast.LENGTH_SHORT).show();
                    })
                    .show();
            }
        }

        //检查更新
        if(!BuildConfig.VERSION_NAME.equals(GlobalDataHolder.getLatestVersion())) {
            GlobalUtils.showToast(this, getString(R.string.toast_update_available), false);
        }
    }

    // ★★★ 新增：设置输入框监听和按钮模式切换 ★★★
    private void setupInputListener() {
        switchToVoiceMode();

        etUserInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s.toString().trim().length() > 0) {
                    switchToSendMode();
                } else {
                    switchToVoiceMode();
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    // ★★★ 切换到发送模式 ★★★
    private void switchToSendMode() {
        if (btSend.getTag() != null && btSend.getTag().equals("SEND")) return;

        btSend.setImageResource(R.drawable.ic_send_round); 
        btSend.setTag("SEND");

        btSend.setOnClickListener(view -> {
            if(webScraper.isLoading() || (currentCall != null && !currentCall.isCanceled())){
                if (webScraper.isLoading()) webScraper.stopLoading();
                if (currentCall != null) currentCall.cancel();
                
                if(tvGptReply != null)
                    tvGptReply.setText(R.string.text_cancel_web);
                btSend.setImageResource(R.drawable.ic_send_round);
            } else {
                ttsManager.stop();
                sendQuestion(null);
                etUserInput.setText("");
            }
        });
        btSend.setOnLongClickListener(null); 
    }

    // ★★★ 切换到语音模式 ★★★
    private void switchToVoiceMode() {
        if (btSend.getTag() != null && btSend.getTag().equals("VOICE")) return;

        btSend.setImageResource(R.drawable.ic_mic_round); 
        btSend.setTag("VOICE");

        btSend.setOnClickListener(view -> {
            Intent broadcastIntent = new Intent("com.skythinker.gptassistant.KEY_SPEECH_START");
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent);
        });

        btSend.setOnLongClickListener(view -> {
            showLanguageSelectionDialog();
            return true;
        });
    }

    // ★★★ 显示语言选择对话框 ★★★
    private void showLanguageSelectionDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择语音识别语言");

        GridLayout gridLayout = new GridLayout(this);
        gridLayout.setColumnCount(3);
        gridLayout.setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10));

        addLangOption(gridLayout, "🇨🇳\n中文", GlobalDataHolder.LANG_ZH);
        addLangOption(gridLayout, "🇺🇸\nEnglish", GlobalDataHolder.LANG_EN);
        addLangOption(gridLayout, "🇲🇲\n缅甸语", GlobalDataHolder.LANG_MM);

        ScrollView sv = new ScrollView(this);
        sv.addView(gridLayout);
        builder.setView(sv);
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void addLangOption(GridLayout grid, String label, String langCode) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(16);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dpToPx(15), dpToPx(15), dpToPx(15), dpToPx(15));
        tv.setBackgroundResource(android.R.drawable.btn_default);

        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.setMargins(dpToPx(5), dpToPx(5), dpToPx(5), dpToPx(5));
        tv.setLayoutParams(params);

        tv.setOnClickListener(v -> {
            GlobalDataHolder.getInstance(this).setCurrentLanguage(langCode);
            Toast.makeText(this, "已切换为: " + langCode, Toast.LENGTH_SHORT).show();
        });
        grid.addView(tv);
    }

    private void setAsrClient(String type) {
        if(asrClient != null) {
            asrClient.destroy();
        }
        if(type.equals("baidu")) {
            asrClient = new BaiduAsrClient(this);
            asrClient.setCallback(asrCallback);
        } else if (type.equals("hms")) {
            asrClient = new HmsAsrClient(this);
            asrClient.setCallback(asrCallback);
        } else if (type.equals("whisper")) {
            asrClient = new WhisperAsrClient(this, GlobalDataHolder.getGptApiHost(), GlobalDataHolder.getGptApiKey());
            asrClient.setCallback(asrCallback);
        } else if (type.equals("google")) {
            asrClient = new GoogleAsrClient(this);
            asrClient.setCallback(asrCallback);
        }
    }

    private void setNetworkEnabled(boolean enabled) {
        // 逻辑已移至 sendQuestion 的 tools 构建中
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode == 0) { 
            int tabNum = GlobalDataHolder.getTabDataList().size(); 
            if(selectedTab >= tabNum)
                selectedTab = tabNum - 1;
            switchToTemplate(selectedTab);

            updateModelSpinner(); 

            if(asrClient instanceof WhisperAsrClient) {
                ((WhisperAsrClient) asrClient).setApiInfo(GlobalDataHolder.getGptApiHost(), GlobalDataHolder.getGptApiKey());
            }
            
        } else if((requestCode == 1 || requestCode == 2) && resultCode == RESULT_OK) { 
            Uri uri = requestCode == 1 ? photoUri : data.getData(); 
            addAttachment(uri);
        } else if(requestCode == 3 && resultCode == RESULT_OK) { 
            if(data.hasExtra("id")) {
                long id = data.getLongExtra("id", -1);
                Log.d("MainActivity", "onActivityResult 3: id=" + id);
                Conversation conversation = chatManager.getConversation(id);
                chatManager.removeConversation(id);
                conversation.updateTime();
                reloadConversation(conversation);
            }
        } else if(requestCode == 4 && resultCode == RESULT_OK) { 
            try {
                ArrayList<Uri> uris = new ArrayList<>();
                ClipData clipData = data.getClipData();
                if(clipData != null) { 
                    for (int i = 0; i < clipData.getItemCount(); i++) {
                        uris.add(clipData.getItemAt(i).getUri());
                    }
                } else { 
                    Uri uri = data.getData();
                    if(uri != null)
                        uris.add(uri);
                }
                for (Uri uri : uris) {
                    addAttachment(uri);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void scrollChatAreaToBottom() {
        svChatArea.post(() -> {
            int delta = svChatArea.getChildAt(0).getBottom()
                    - (svChatArea.getHeight() + svChatArea.getScrollY());
            if(delta != 0)
                svChatArea.smoothScrollBy(0, delta);
        });
    }

    private void updateModelSpinner() {
        Spinner spModels = findViewById(R.id.sp_main_model);
        List<String> models = new ArrayList<>(Arrays.asList(getResources().getStringArray(R.array.models))); 
        models.addAll(GlobalDataHolder.getCustomModels()); 
        ArrayAdapter<String> modelsAdapter = new ArrayAdapter<String>(this, R.layout.main_model_spinner_item, models) { 
            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) { 
                TextView tv = (TextView) super.getDropDownView(position, convertView, parent);
                if(spModels.getSelectedItemPosition() == position) {
                    tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                } else {
                    tv.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
                }
                return tv;
            }
        };
        modelsAdapter.setDropDownViewResource(R.layout.model_spinner_dropdown_item); 
        spModels.setAdapter(modelsAdapter);
        spModels.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { 
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                GlobalDataHolder.saveGptApiInfo(GlobalDataHolder.getGptApiHost(), GlobalDataHolder.getGptApiKey(), adapterView.getItemAtPosition(i).toString(), GlobalDataHolder.getCustomModels());
                modelsAdapter.notifyDataSetChanged();
            }
            public void onNothingSelected(AdapterView<?> adapterView) { }
        });
        for(int i = 0; i < modelsAdapter.getCount(); i++) { 
            if(modelsAdapter.getItem(i).equals(GlobalDataHolder.getGptModel())) {
                spModels.setSelection(i);
                break;
            }
            if(i == modelsAdapter.getCount() - 1) { 
                spModels.setSelection(0);
            }
        }
    }

    private void updateTabListView() {
        LinearLayout tabList = findViewById(R.id.tabs_layout);
        tabList.removeAllViews();
        List<PromptTabData> tabDataList = GlobalDataHolder.getTabDataList(); 
        for (int i = 0; i < tabDataList.size(); i++) { 
            PromptTabData tabData = tabDataList.get(i);
            Button tabBtn = new Button(this);
            tabBtn.setText(tabData.getTitle());
            tabBtn.setTextSize(16);
            if(i == selectedTab) {
                tabBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                tabBtn.setBackgroundResource(R.drawable.tab_background_selected);
            } else {
                tabBtn.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
                tabBtn.setBackgroundResource(R.drawable.tab_background_unselected);
            }
            ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, 20, 0);
            tabBtn.setLayoutParams(params);
            int finalI = i;
            tabBtn.setOnClickListener(view -> { 
                if(finalI != selectedTab) {
                    switchToTemplate(finalI);
                    if(multiChatList.size() > 0)
                        (findViewById(R.id.cv_new_chat)).performClick();
                }
            });
            tabList.addView(tabBtn);
        }
    }

    private void updateTemplateParamsView() {
        LinearLayout llParams = findViewById(R.id.ll_template_params);
        llParams.removeAllViews();
        if(currentTemplateParams.containsKey("input")) {
            for (String inputKey : currentTemplateParams.getJSONObject("input").keySet()) {
                LinearLayout llOuter = new LinearLayout(this); 
                llOuter.setOrientation(LinearLayout.HORIZONTAL);
                llOuter.setGravity(Gravity.CENTER);
                llOuter.setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10));
                TextView tv = new TextView(this); 
                tv.setText(inputKey);
                tv.setTextColor(Color.BLACK);
                tv.setTextSize(16);
                tv.setPadding(0, 0, dpToPx(10), 0);
                tv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0));
                llOuter.addView(tv);
                JSONObject inputItem = currentTemplateParams.getJSONObject("input").getJSONObject(inputKey);
                if(inputItem.getStr("type").equals("text")) { 
                    EditText et = new EditText(this);
                    et.setBackgroundColor(Color.TRANSPARENT);
                    et.setTextSize(16);
                    et.setHint(R.string.text_temp_param_input_hint);
                    et.setTextColor(Color.BLACK);
                    et.setSingleLine(false);
                    et.setMaxHeight(dpToPx(80));
                    et.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
                    et.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                    et.setPadding(0, 0, 0, 0);
                    llOuter.addView(et);
                } else if(inputItem.getStr("type").equals("select")) { 
                    Spinner sp = new Spinner(this, Spinner.MODE_DROPDOWN);
                    sp.setBackgroundColor(Color.TRANSPARENT);
                    sp.setPadding(0, 0, 0, 0);
                    sp.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                    sp.setPopupBackgroundDrawable(ContextCompat.getDrawable(this, R.drawable.spinner_dropdown_background));
                    List<String> options = new ArrayList<>();
                    JSONArray itemsArray = inputItem.getJSONArray("items");
                    for(int i = 0; i < itemsArray.size(); i++) {
                        options.add(itemsArray.getJSONObject(i).getStr("name"));
                    }
                    ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, R.layout.param_spinner_item, options) {
                        @Override
                        public View getDropDownView(int position, View convertView, ViewGroup parent) {
                            TextView tv = (TextView) super.getDropDownView(position, convertView, parent);
                            if(sp.getSelectedItemPosition() == position) { 
                                tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                            } else { 
                                tv.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
                            }
                            return tv;
                        }
                    };
                    adapter.setDropDownViewResource(R.layout.param_spinner_dropdown_item);
                    sp.setAdapter(adapter);
                    llOuter.addView(sp);
                }
                llOuter.setTag(inputKey);
                llParams.addView(llOuter);
            }
        }

        if(llParams.getChildCount() == 0) { 
            ((CardView) llParams.getParent()).setVisibility(View.GONE);
        } else {
            ((CardView) llParams.getParent()).setVisibility(View.VISIBLE);
        }
    }

    private JSONObject getTemplateParamsFromView() {
        JSONObject params = new JSONObject();
        LinearLayout llParams = findViewById(R.id.ll_template_params);
        for (int i = 0; i < llParams.getChildCount(); i++) {
            LinearLayout llOuter = (LinearLayout) llParams.getChildAt(i);
            String inputKey = (String) llOuter.getTag();
            if(llOuter.getChildAt(1) instanceof EditText) {
                EditText et = (EditText) llOuter.getChildAt(1);
                params.putOpt(inputKey, et.getText().toString());
            } else if(llOuter.getChildAt(1) instanceof Spinner) {
                Spinner sp = (Spinner) llOuter.getChildAt(1);
                params.putOpt(inputKey, sp.getSelectedItem());
            }
        }
        return params;
    }

    private void switchToTemplate(int tabIndex) {
        selectedTab = tabIndex;
        if(GlobalDataHolder.getSelectedTab() != -1) {
            GlobalDataHolder.saveSelectedTab(selectedTab);
        }
        currentTemplateParams = GlobalDataHolder.getTabDataList().get(selectedTab).parseParams();
        Log.d("MainActivity", "switch template: params=" + currentTemplateParams);
        setNetworkEnabled(currentTemplateParams.getBool("network", GlobalDataHolder.getEnableInternetAccess()));
        updateTabListView();
        updateTemplateParamsView();
    }

    private LinearLayout addChatView(ChatRole role, String content, ArrayList<ChatMessage.Attachment> attachments) {
        ViewGroup.MarginLayoutParams iconParams = new ViewGroup.MarginLayoutParams(dpToPx(30), dpToPx(30)); 
        iconParams.setMargins(dpToPx(4), dpToPx(12), dpToPx(4), dpToPx(12));

        ViewGroup.MarginLayoutParams contentParams = new ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); 
        contentParams.setMargins(dpToPx(4), dpToPx(15), dpToPx(4), dpToPx(15));

        LinearLayout.LayoutParams popupIconParams = new LinearLayout.LayoutParams(dpToPx(30), dpToPx(30)); 
        popupIconParams.setMargins(dpToPx(5), dpToPx(5), dpToPx(5), dpToPx(5));

        LinearLayout llOuter = new LinearLayout(this); 
        llOuter.setOrientation(LinearLayout.HORIZONTAL);
        if(role == ChatRole.ASSISTANT) 
            llOuter.setBackgroundColor(Color.parseColor("#0A000000"));

        ImageView ivIcon = new ImageView(this); 
        if(role == ChatRole.USER)
            ivIcon.setImageResource(R.drawable.chat_user_icon);
        else
            ivIcon.setImageResource(R.drawable.chat_gpt_icon);
        ivIcon.setLayoutParams(iconParams);

        TextView tvContent = new TextView(this); 
        if(role == ChatRole.USER) {
            SpannableStringBuilder stringBuilder = new SpannableStringBuilder();
            stringBuilder.append(content);
            if (attachments != null) { 
                boolean hasImageAttachment = false;
                for(ChatMessage.Attachment attachment : attachments) {
                    if(attachment.type == ChatMessage.Attachment.Type.IMAGE) {
                        if(!hasImageAttachment) {
                            stringBuilder.append("\ni");
                            hasImageAttachment = true;
                        } else {
                            stringBuilder.append(" i");
                        }
                        Bitmap bitmap = base64ToBitmap(attachment.content);
                        int maxSize = dpToPx(120);
                        bitmap = resizeBitmap(bitmap, maxSize, maxSize);
                        ImageSpan imageSpan = new ImageSpan(this, bitmap);
                        stringBuilder.setSpan(imageSpan, stringBuilder.length() - 1, stringBuilder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        stringBuilder.setSpan(new ClickableSpan() {
                            @Override
                            public void onClick(@NonNull View view) {
                                Bitmap bitmap = base64ToBitmap(attachment.content);
                                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                                LayoutInflater inflater = LayoutInflater.from(MainActivity.this);
                                View dialogView = inflater.inflate(R.layout.image_preview_dialog, null);
                                AlertDialog dialog = builder.create();
                                dialog.show();
                                dialog.getWindow().setContentView(dialogView);
                                ((ImageView) dialogView.findViewById(R.id.iv_image_preview)).setImageBitmap(bitmap);
                                ((TextView) dialogView.findViewById(R.id.tv_image_preview_size)).setText(String.format("%s x %s", bitmap.getWidth(), bitmap.getHeight()));
                                dialogView.findViewById(R.id.cv_image_preview_cancel).setOnClickListener(view1 -> dialog.dismiss());
                                dialogView.findViewById(R.id.cv_image_preview_del).setVisibility(View.GONE);
                                dialogView.findViewById(R.id.cv_image_preview_reselect).setVisibility(View.GONE);
                            }
                        }, stringBuilder.length() - 1, stringBuilder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
                for(ChatMessage.Attachment attachment : attachments) {
                    if(attachment.type == ChatMessage.Attachment.Type.TEXT) {
                        stringBuilder.append("\n").append(attachment.name);
                        stringBuilder.setSpan(new ClickableSpan() {
                            @Override
                            public void onClick(@NonNull View view) {
                                new ConfirmDialog(MainActivity.this)
                                        .setTitle(attachment.name)
                                        .setContent(attachment.content)
                                        .setContentAlignment(View.TEXT_ALIGNMENT_TEXT_START)
                                        .setOkButtonVisibility(View.GONE)
                                        .show();
                            }
                        }, stringBuilder.length() - attachment.name.length(), stringBuilder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
            }
            tvContent.setText(stringBuilder);
        } else if(role == ChatRole.ASSISTANT) {
            markdownRenderer.render(tvContent, content);
        }
        tvContent.setTextSize(16);
        tvContent.setTextColor(Color.BLACK);
        tvContent.setLayoutParams(contentParams);
        tvContent.setTextIsSelectable(true);
        tvContent.setMovementMethod(LinkMovementMethod.getInstance());

        LinearLayout llPopup = new LinearLayout(this); 
        llPopup.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        PaintDrawable popupBackground = new PaintDrawable(Color.TRANSPARENT);
        llPopup.setBackground(popupBackground);
        llPopup.setOrientation(LinearLayout.HORIZONTAL);

        PopupWindow popupWindow = new PopupWindow(llPopup, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true); 
        popupWindow.setOutsideTouchable(true);
        ivIcon.setTag(popupWindow); 

        CardView cvDelete = new CardView(this); 
        cvDelete.setForeground(getDrawable(R.drawable.clear_btn));
        cvDelete.setOnClickListener(view -> {
            popupWindow.dismiss();
            ChatMessage chat = (ChatMessage) llOuter.getTag(); 
            if(chat != null) {
                int index = multiChatList.indexOf(chat);
                multiChatList.remove(chat);
                while(--index > 0 && (multiChatList.get(index).role == ChatRole.FUNCTION
                        || (multiChatList.get(index).role == ChatRole.ASSISTANT && multiChatList.get(index).toolCalls.size() > 0))) 
                    multiChatList.remove(index);
            }
            if(tvContent == tvGptReply) { 
                if(currentCall != null) currentCall.cancel();
                ttsManager.stop();
            }
            llChatList.removeView(llOuter);
            if(llChatList.getChildCount() == 0) 
                clearChatListView();
        });
        llPopup.addView(cvDelete);

        CardView cvDelBelow = new CardView(this); 
        cvDelBelow.setForeground(getDrawable(R.drawable.del_below_btn));
        cvDelBelow.setOnClickListener(view -> {
            popupWindow.dismiss();
            int index = llChatList.indexOfChild(llOuter);
            while(llChatList.getChildCount() > index && llChatList.getChildAt(0) instanceof LinearLayout) { 
                PopupWindow pw = (PopupWindow) ((LinearLayout) llChatList.getChildAt(llChatList.getChildCount() - 1)).getChildAt(0).getTag();
                ((LinearLayout) pw.getContentView()).getChildAt(0).performClick();
            }
        });
        llPopup.addView(cvDelBelow);

        if(role == ChatRole.USER) { 
            CardView cvEdit = new CardView(this); 
            cvEdit.setForeground(getDrawable(R.drawable.edit_btn));
            cvEdit.setOnClickListener(view -> {
                popupWindow.dismiss();
                ChatMessage chat = (ChatMessage) llOuter.getTag(); 
                String text = chat.contentText;
                if(chat.attachments.size() > 0) { 
                    selectedAttachments.clear();
                    selectedAttachments.addAll(chat.attachments); 
                } else {
                    selectedAttachments.clear();
                }
                updateAttachmentButton(); 
                etUserInput.setText(text); 
                cvDelBelow.performClick(); 
            });
            llPopup.addView(cvEdit);

            CardView cvRetry = new CardView(this); 
            cvRetry.setForeground(getDrawable(R.drawable.retry_btn));
            cvRetry.setOnClickListener(view -> {
                popupWindow.dismiss();
                ChatMessage chat = (ChatMessage) llOuter.getTag(); 
                String text = chat.contentText;
                if(chat.attachments.size() > 0) { 
                    selectedAttachments.clear();
                    selectedAttachments.addAll(chat.attachments); 
                } else {
                    selectedAttachments.clear();
                }
                cvDelBelow.performClick(); 
                sendQuestion(text); 
            });
            llPopup.addView(cvRetry);
        }

        CardView cvCopy = new CardView(this); 
        cvCopy.setForeground(getDrawable(R.drawable.copy_btn));
        cvCopy.setOnClickListener(view -> { 
            popupWindow.dismiss();
            ChatMessage chat = (ChatMessage) llOuter.getTag(); 
            if(chat == null || chat.role != ChatRole.USER) {
                GlobalUtils.copyToClipboard(this, tvContent.getText().toString()); 
            } else {
                GlobalUtils.copyToClipboard(this, chat.contentText); 
            }
            Toast.makeText(this, R.string.toast_clipboard, Toast.LENGTH_SHORT).show();
        });
        llPopup.addView(cvCopy);

        for(int i = 0; i < llPopup.getChildCount(); i++) { 
            CardView cvBtn = (CardView) llPopup.getChildAt(i);
            cvBtn.setLayoutParams(popupIconParams);
            cvBtn.setCardBackgroundColor(Color.WHITE);
            cvBtn.setRadius(dpToPx(5));
        }

        ivIcon.setOnClickListener(view -> { 
            popupWindow.showAsDropDown(view, dpToPx(30), -dpToPx(35));
        });

        llOuter.addView(ivIcon);
        llOuter.addView(tvContent);

        llChatList.addView(llOuter);

        return llOuter;
    }

    // ★★★ 核心重构：发送提问，使用 OkHttp + Hutool 实现流式对话与 Function Calling ★★★
    private void sendQuestion(String input){
        boolean isMultiChat = currentTemplateParams.getBool("chat", multiChat);

        if(!isMultiChat) { 
            ((CardView) findViewById(R.id.cv_new_chat)).performClick();
        }

        String userInput = (input == null) ? etUserInput.getText().toString() : input;
        if(multiChatList.size() == 0 && input == null) { 
            PromptTabData tabData = GlobalDataHolder.getTabDataList().get(selectedTab);
            String template = tabData.getFormattedPrompt(getTemplateParamsFromView());
            if(currentTemplateParams.getBool("system", false)) {
                multiChatList.add(new ChatMessage(ChatRole.SYSTEM).setText(template));
                multiChatList.add(new ChatMessage(ChatRole.USER).setText(userInput));
            } else {
                if(!template.contains("%input%") && !template.contains("${input}"))
                    template += "${input}";
                String question = template.replace("%input%", userInput).replace("${input}", userInput);
                multiChatList.add(new ChatMessage(ChatRole.USER).setText(question));
            }
            currentConversation.title = String.format("%s%s%s",
                    tabData.getTitle(),
                    (!tabData.getTitle().isEmpty() && !userInput.isEmpty()) ? " | " : "",
                    userInput.substring(0, Math.min(100, userInput.length())).replaceAll("\n", " ")); 
        } else {
            multiChatList.add(new ChatMessage(ChatRole.USER).setText(userInput));
        }

        if(selectedAttachments.size() > 0) { 
            for (ChatMessage.Attachment attachment : selectedAttachments) {
                multiChatList.get(multiChatList.size() - 1).addAttachment(attachment);
            }
        }

        if(llChatList.getChildCount() > 0 && llChatList.getChildAt(0) instanceof TextView) { 
            llChatList.removeViewAt(0);
        }

        if(GlobalDataHolder.getOnlyLatestWebResult()) { 
            for (int i = 0; i < multiChatList.size(); i++) {
                ChatMessage chatItem = multiChatList.get(i);
                if (chatItem.role == ChatRole.FUNCTION) {
                    multiChatList.remove(i);
                    i--;
                    if(i > 0 && multiChatList.get(i).role == ChatRole.ASSISTANT) { 
                        multiChatList.remove(i);
                        i--;
                    }
                }
            }
        }

        LinearLayout llInput = addChatView(ChatRole.USER, isMultiChat ? multiChatList.get(multiChatList.size() - 1).contentText : userInput, multiChatList.get(multiChatList.size() - 1).attachments);
        LinearLayout llReply = addChatView(ChatRole.ASSISTANT, getString(R.string.text_waiting_reply), null);

        llInput.setTag(multiChatList.get(multiChatList.size() - 1)); 

        tvGptReply = (TextView) llReply.getChildAt(1);

        scrollChatAreaToBottom();

        chatApiBuffer = "";
        ttsSentenceEndIndex = 0;
        
        if (BuildConfig.DEBUG && userInput.startsWith("#markdowndebug\n")) { 
            markdownRenderer.render(tvGptReply, userInput.replace("#markdowndebug\n", ""));
        } else {
            String apiKey = GlobalDataHolder.getGptApiKey();
            if (apiKey == null || apiKey.isEmpty()) {
                Toast.makeText(this, "请先在设置中配置 API Key", Toast.LENGTH_LONG).show();
                return;
            }

            JSONObject jsonBody = new JSONObject();
            jsonBody.set("model", GlobalDataHolder.getGptModel());
            jsonBody.set("stream", true);
            jsonBody.set("temperature", GlobalDataHolder.getGptTemperature());
            
            JSONArray messages = new JSONArray();
            for (ChatMessage msg : multiChatList) {
                JSONObject m = new JSONObject();
                m.set("role", msg.role.toString().toLowerCase());
                m.set("content", msg.contentText);
                if (msg.role == ChatRole.ASSISTANT && msg.toolCalls.size() > 0) {
                    JSONArray toolCalls = new JSONArray();
                    for (ChatMessage.ToolCall tc : msg.toolCalls) {
                        JSONObject t = new JSONObject();
                        // ★★★ 修复：使用 id 而不是 toolId，因为 ToolCall 类可能没有 toolId 字段 ★★★
                        t.set("id", tc.id); 
                        t.set("type", "function");
                        JSONObject func = new JSONObject();
                        func.set("name", tc.functionName);
                        func.set("arguments", tc.arguments);
                        t.set("function", func);
                        toolCalls.add(t);
                    }
                    m.set("tool_calls", toolCalls);
                }
                if (msg.role == ChatRole.FUNCTION) {
                    m.set("role", "tool");
                    // ★★★ 修复：从 toolCalls 列表中获取 ID，因为 ChatMessage 本身没有 toolCallId 字段 ★★★
                    if (msg.toolCalls.size() > 0) {
                        m.set("tool_call_id", msg.toolCalls.get(0).id);
                        m.set("name", msg.toolCalls.get(0).functionName);
                    } else {
                        // Fallback if empty (should not happen for FUNCTION role)
                        m.set("tool_call_id", "unknown");
                        m.set("name", "unknown");
                    }
                }
                messages.add(m);
            }
            jsonBody.set("messages", messages);

            if (currentTemplateParams.getBool("network", GlobalDataHolder.getEnableInternetAccess())) {
                JSONArray tools = new JSONArray();
                JSONObject tool = new JSONObject();
                tool.set("type", "function");
                JSONObject func = new JSONObject();
                func.set("name", "get_html_text");
                func.set("description", "get all innerText and links of a web page");
                JSONObject params = new JSONObject();
                params.set("type", "object");
                JSONObject props = new JSONObject();
                JSONObject urlProp = new JSONObject();
                urlProp.set("type", "string");
                urlProp.set("description", "html url");
                props.set("url", urlProp);
                params.set("properties", props);
                params.set("required", new JSONArray().put("url"));
                func.set("parameters", params);
                tool.set("function", func);
                tools.add(tool);
                jsonBody.set("tools", tools);
            }

            // ★★★ 修复：参数顺序交换，适配 OkHttp 3.x/4.x 兼容性 ★★★
            RequestBody body = RequestBody.create(
                    MediaType.parse("application/json; charset=utf-8"),
                    jsonBody.toString()
            );

            String host = GlobalDataHolder.getGptApiHost();
            if (host.endsWith("/")) host = host.substring(0, host.length() - 1);
            String url = host + "/chat/completions";

            Request request = new Request.Builder()
                    .url(url) 
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(body)
                    .build();

            btSend.setImageResource(R.drawable.cancel_btn);
            
            currentCall = okHttpClient.newCall(request);
            currentCall.enqueue(new Callback() {
                private final StringBuilder functionArgsBuffer = new StringBuilder();
                private String currentToolId = null;
                private String currentFunctionName = null;
                private boolean isFunctionCall = false;

                @Override
                public void onFailure(Call call, IOException e) {
                    if (call.isCanceled()) return;
                    runOnUiThread(() -> {
                        tvGptReply.setText("请求失败: " + e.getMessage());
                        btSend.setImageResource(R.drawable.ic_send_round);
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        runOnUiThread(() -> {
                            tvGptReply.setText("服务器错误: " + response.code());
                            btSend.setImageResource(R.drawable.ic_send_round);
                        });
                        return;
                    }

                    InputStream is = response.body().byteStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6).trim();
                            if (data.equals("[DONE]")) break;
                            try {
                                JSONObject json = JSONUtil.parseObj(data);
                                JSONArray choices = json.getJSONArray("choices");
                                if (choices != null && !choices.isEmpty()) {
                                    JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
                                    
                                    if (delta != null && delta.containsKey("content")) {
                                        String content = delta.getStr("content");
                                        if (content != null) {
                                            chatApiBuffer += content;
                                            runOnUiThread(() -> {
                                                markdownRenderer.render(tvGptReply, chatApiBuffer);
                                                scrollChatAreaToBottom();
                                                handleTts(chatApiBuffer);
                                            });
                                        }
                                    }
                                    
                                    if (delta != null && delta.containsKey("tool_calls")) {
                                        JSONArray toolCalls = delta.getJSONArray("tool_calls");
                                        JSONObject toolCall = toolCalls.getJSONObject(0);
                                        if (toolCall.containsKey("id")) {
                                            currentToolId = toolCall.getStr("id");
                                            currentFunctionName = toolCall.getJSONObject("function").getStr("name");
                                            isFunctionCall = true;
                                            functionArgsBuffer.setLength(0); 
                                        }
                                        if (toolCall.containsKey("function") && toolCall.getJSONObject("function").containsKey("arguments")) {
                                            functionArgsBuffer.append(toolCall.getJSONObject("function").getStr("arguments"));
                                        }
                                    }
                                }
                            } catch (Exception e) {
                            }
                        }
                    }

                    runOnUiThread(() -> {
                        if (isFunctionCall) {
                            handleFunctionCall(currentToolId, currentFunctionName, functionArgsBuffer.toString());
                        } else {
                            multiChatList.add(new ChatMessage(ChatRole.ASSISTANT).setText(chatApiBuffer));
                            ((LinearLayout) tvGptReply.getParent()).setTag(multiChatList.get(multiChatList.size() - 1));
                            
                            if (currentTemplateParams.getBool("speak", ttsEnabled) && chatApiBuffer.length() > ttsSentenceEndIndex) {
                                ttsManager.speak(chatApiBuffer.substring(ttsSentenceEndIndex));
                            }
                            
                            btSend.setImageResource(R.drawable.ic_send_round);
                        }
                    });
                }
            });

            selectedAttachments.clear();
            updateAttachmentButton(); 
        }
    }

    private void handleTts(String fullText) {
        if (!currentTemplateParams.getBool("speak", ttsEnabled)) return;
        
        if (fullText.startsWith("<think>\n") && !fullText.contains("\n</think>\n")) {
            ttsSentenceEndIndex = fullText.length();
            return;
        }

        if (ttsSentenceEndIndex < fullText.length()) {
            int nextSentenceEndIndex = fullText.length();
            boolean found = false;
            for (String separator : ttsSentenceSeparator) {
                int index = fullText.indexOf(separator, ttsSentenceEndIndex);
                if (index != -1 && index < nextSentenceEndIndex) {
                    nextSentenceEndIndex = index + separator.length();
                    found = true;
                }
            }
            if (found) {
                String sentence = fullText.substring(ttsSentenceEndIndex, nextSentenceEndIndex);
                ttsSentenceEndIndex = nextSentenceEndIndex;
                ttsManager.speak(sentence); 
            }
        }
    }

    private void handleFunctionCall(String toolId, String functionName, String arguments) {
        Log.d(TAG, "Function Call: " + functionName + " args: " + arguments);
        
        ChatMessage assistantMsg = new ChatMessage(ChatRole.ASSISTANT);
        assistantMsg.addFunctionCall(toolId, functionName, arguments, null);
        multiChatList.add(assistantMsg);

        if ("get_html_text".equals(functionName)) {
            try {
                JSONObject args = new JSONObject(arguments);
                String url = args.getStr("url");
                markdownRenderer.render(tvGptReply, String.format(getString(R.string.text_visiting_web_prefix) + "[%s](%s)", URLDecoder.decode(url), url));
                
                webScraper.load(url, new WebScraper.Callback() {
                    @Override
                    public void onLoadResult(String result) {
                        multiChatList.add(new ChatMessage(ChatRole.FUNCTION).addFunctionCall(toolId, functionName, arguments, result));
                        sendQuestion(null); 
                    }

                    @Override
                    public void onLoadFail(String message) {
                        multiChatList.add(new ChatMessage(ChatRole.FUNCTION).addFunctionCall(toolId, functionName, arguments, "Error: " + message));
                        sendQuestion(null);
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
                multiChatList.add(new ChatMessage(ChatRole.FUNCTION).addFunctionCall(toolId, functionName, arguments, "Error parsing arguments"));
                sendQuestion(null);
            }
        } else if ("exit_voice_chat".equals(functionName)) {
            if (multiVoice) findViewById(R.id.cv_voice_chat).performClick();
            multiChatList.add(new ChatMessage(ChatRole.FUNCTION).addFunctionCall(toolId, functionName, arguments, "OK"));
            sendQuestion(null);
        } else {
            multiChatList.add(new ChatMessage(ChatRole.FUNCTION).addFunctionCall(toolId, functionName, arguments, "Function not found"));
            sendQuestion(null);
        }
    }

    private PopupWindow getAttachmentPopupWindow() {
        LinearLayout.LayoutParams popupParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); 
        popupParams.setMargins(0, 0, 0, 0);

        LinearLayout.LayoutParams deleteIconParams = new LinearLayout.LayoutParams(dpToPx(30), dpToPx(30)); 
        deleteIconParams.setMargins(dpToPx(5), 0, dpToPx(5), 0);

        LinearLayout.LayoutParams filenameCardParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpToPx(30)); 
        filenameCardParams.setMargins(dpToPx(5), 0, dpToPx(5), 0);

        LinearLayout.LayoutParams uploadIconParams = new LinearLayout.LayoutParams(dpToPx(30), dpToPx(30)); 
        uploadIconParams.setMargins(dpToPx(5), 0, dpToPx(5), 0);

        LinearLayout llPopup = new LinearLayout(this);
        llPopup.setOrientation(LinearLayout.VERTICAL);
        llPopup.setGravity(Gravity.LEFT);
        llPopup.setLayoutParams(popupParams);

        PopupWindow popupWindow = new PopupWindow(llPopup, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setOutsideTouchable(true);

        ScrollView svAttachment = new ScrollView(this);
        svAttachment.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        svAttachment.setVerticalScrollBarEnabled(false);
        LinearLayout llAttachmentList = new LinearLayout(this);
        llAttachmentList.setOrientation(LinearLayout.VERTICAL);
        llAttachmentList.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if(selectedAttachments.size() > 0) {
            for (ChatMessage.Attachment attachment : selectedAttachments) {
                Log.d("MainActivity", "getAttachmentPopupWindow name: " + attachment.name);
                LinearLayout llAttachment = new LinearLayout(this);
                llAttachment.setOrientation(LinearLayout.HORIZONTAL);
                llAttachment.setGravity(Gravity.START);
                llAttachment.setPadding(dpToPx(10), dpToPx(5), dpToPx(10), dpToPx(5));
                llAttachment.setTag(attachment);

                CardView cvFilename = new CardView(this);
                cvFilename.setLayoutParams(filenameCardParams);
                cvFilename.setCardBackgroundColor(Color.WHITE);
                cvFilename.setRadius(dpToPx(5));
                cvFilename.setContentPadding(dpToPx(5), 0, dpToPx(5), 0);
                cvFilename.setElevation(0);

                TextView tvFilename = new TextView(this);
                tvFilename.setText(attachment.name);
                tvFilename.setTextColor(Color.BLACK);
                tvFilename.setTextSize(14);
                tvFilename.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                tvFilename.setSingleLine(true);
                tvFilename.setGravity(Gravity.CENTER);

                cvFilename.addView(tvFilename);

                cvFilename.setOnClickListener(view -> { 
                    if (attachment.type == ChatMessage.Attachment.Type.IMAGE) { 
                        Bitmap bitmap = base64ToBitmap(attachment.content);
                        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                        LayoutInflater inflater = LayoutInflater.from(MainActivity.this);
                        View dialogView = inflater.inflate(R.layout.image_preview_dialog, null);
                        AlertDialog dialog = builder.create();
                        dialog.show();
                        dialog.getWindow().setContentView(dialogView);
                        ((ImageView) dialogView.findViewById(R.id.iv_image_preview)).setImageBitmap(bitmap);
                        ((TextView) dialogView.findViewById(R.id.tv_image_preview_size)).setText(String.format("%s x %s", bitmap.getWidth(), bitmap.getHeight()));
                        dialogView.findViewById(R.id.cv_image_preview_cancel).setOnClickListener(view1 -> dialog.dismiss());
                        dialogView.findViewById(R.id.cv_image_preview_del).setVisibility(View.GONE);
                        dialogView.findViewById(R.id.cv_image_preview_reselect).setVisibility(View.GONE);
                    } else { 
                        new ConfirmDialog(MainActivity.this)
                                .setTitle(attachment.name)
                                .setContent(attachment.content)
                                .setContentAlignment(View.TEXT_ALIGNMENT_TEXT_START)
                                .setOkButtonVisibility(View.GONE)
                                .show();
                    }
                });

                CardView cvDelete = new CardView(this);
                cvDelete.setLayoutParams(deleteIconParams);
                cvDelete.setForeground(getDrawable(R.drawable.close_btn));
                cvDelete.setCardBackgroundColor(Color.WHITE);
                cvDelete.setElevation(0);
                cvDelete.setOnClickListener(view -> { 
                    llAttachmentList.removeView(llAttachment);
                    selectedAttachments.remove(attachment);
                    if (llAttachmentList.getChildCount() == 0) {
                        llAttachmentList.setVisibility(View.GONE);
                    }
                    updateAttachmentButton(); 
                });

                llAttachment.addView(cvDelete);
                llAttachment.addView(cvFilename);
                llAttachmentList.addView(llAttachment);
            }
        }

        svAttachment.addView(llAttachmentList);
        llPopup.addView(svAttachment);

        LinearLayout llUpload = new LinearLayout(this);
        llUpload.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0));
        llUpload.setOrientation(LinearLayout.HORIZONTAL);
        llUpload.setPadding(dpToPx(10), dpToPx(5), dpToPx(10), dpToPx(5));
        llUpload.setGravity(Gravity.START);

        CardView cvTakePhoto = new CardView(this);
        cvTakePhoto.setLayoutParams(uploadIconParams);
        cvTakePhoto.setForeground(getDrawable(R.drawable.camera_btn));
        cvTakePhoto.setCardBackgroundColor(Color.WHITE);
        cvTakePhoto.setElevation(0);
        cvTakePhoto.setOnClickListener(view -> {
            popupWindow.dismiss();
            photoUri = FileProvider.getUriForFile(MainActivity.this, BuildConfig.APPLICATION_ID + ".provider", new File(getCacheDir(), "photo.jpg"));
            Intent intent=new Intent();
            intent.setAction(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
            startActivityForResult(intent, 1);
        });

        CardView cvSelectPhoto = new CardView(this);
        cvSelectPhoto.setLayoutParams(uploadIconParams);
        cvSelectPhoto.setForeground(getDrawable(R.drawable.image_btn));
        cvSelectPhoto.setCardBackgroundColor(Color.WHITE);
        cvSelectPhoto.setElevation(0);
        cvSelectPhoto.setOnClickListener(view -> {
            popupWindow.dismiss();
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setType("image/*");
            startActivityForResult(intent, 2);
        });

        CardView cvSelectDocument = new CardView(this);
        cvSelectDocument.setLayoutParams(uploadIconParams);
        cvSelectDocument.setForeground(getDrawable(R.drawable.file_btn));
        cvSelectDocument.setCardBackgroundColor(Color.WHITE);
        cvSelectDocument.setElevation(0);
        cvSelectDocument.setOnClickListener(view -> {
            popupWindow.dismiss();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            String mimeTypes[] = {"text/plain", "application/pdf",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            };
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, 4);
        });

        CardView cvDeleteAll = new CardView(this);
        cvDeleteAll.setLayoutParams(uploadIconParams);
        cvDeleteAll.setForeground(getDrawable(R.drawable.clear_btn));
        cvDeleteAll.setCardBackgroundColor(Color.WHITE);
        cvDeleteAll.setElevation(0);
        cvDeleteAll.setOnClickListener(view -> {
            llAttachmentList.removeAllViews();
            selectedAttachments.clear();
            updateAttachmentButton(); 
        });

        llUpload.addView(cvTakePhoto);
        llUpload.addView(cvSelectPhoto);
        llUpload.addView(cvSelectDocument);
        llUpload.addView(cvDeleteAll);
        llPopup.addView(llUpload);

        llPopup.setOnClickListener(view -> { 
            if (popupWindow.isShowing()) {
                popupWindow.dismiss();
            }
        });

        llAttachmentList.setOnClickListener(view -> { 
            if (popupWindow.isShowing()) {
                popupWindow.dismiss();
            }
        });

        return popupWindow;
    }

    private void addAttachment(Uri uri) {
        try {
            Log.d("MainActivity", "addAttachment: uri=" + uri);
            String mimeType = getContentResolver().getType(uri);
            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            String filename = "file";
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                String displayName = cursor.getString(nameIndex);
                if(displayName != null) {
                    filename = displayName;
                }
                cursor.close();
            }
            if (mimeType.startsWith("image/")) {
                Bitmap bitmap = (Bitmap) BitmapFactory.decodeStream(getContentResolver().openInputStream(uri));
                if (GlobalDataHolder.getLimitVisionSize()) {
                    if (bitmap.getWidth() < bitmap.getHeight())
                        bitmap = resizeBitmap(bitmap, 512, 2048);
                    else
                        bitmap = resizeBitmap(bitmap, 2048, 512);
                } else {
                    bitmap = resizeBitmap(bitmap, 2048, 2048);
                }
                selectedAttachments.add(ChatMessage.Attachment.createNew(ChatMessage.Attachment.Type.IMAGE, filename, bitmapToBase64(bitmap), false));
                Log.d("MainActivity", "addImageAttachment: fileName=" + filename + " size=" + bitmap.getWidth() + "x" + bitmap.getHeight());
                updateAttachmentButton(); 
            } else {
                String finalFilename = filename;
                new DocumentParser(this).parseDocument(uri, mimeType, new DocumentParser.ParseCallback() {
                    @Override
                    public void onParseSuccess(String text) {
                        selectedAttachments.add(ChatMessage.Attachment.createNew(ChatMessage.Attachment.Type.TEXT, finalFilename, text, false));
                        Log.d("MainActivity", "addAttachment: fileName=" + finalFilename + " size=" + text.length());
                        runOnUiThread(() -> {
                            updateAttachmentButton(); 
                        });
                    }

                    @Override
                    public void onParseError(Exception e) {
                        runOnUiThread(() -> {
                            Log.e("MainActivity", "addAttachment parse error: " + finalFilename);
                            GlobalUtils.showToast(MainActivity.this, getString(R.string.toast_unsupported_file) + finalFilename, false);
                        });
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateAttachmentButton() {
        TextView tvNumber = findViewById(R.id.tv_attachment_num);
        if(selectedAttachments.size() > 0) {
            btAttachment.setImageResource(R.drawable.attachment_btn_enabled);
            tvNumber.setVisibility(View.VISIBLE);
            String numberText = selectedAttachments.size() < 100 ? String.valueOf(selectedAttachments.size()) : "99+";
            tvNumber.setText(numberText);
        } else {
            btAttachment.setImageResource(R.drawable.attachment_btn);
            tvNumber.setText("0");
            tvNumber.setVisibility(View.GONE);
        }
    }

    private void reloadConversation(Conversation conversation) {
        (findViewById(R.id.cv_new_chat)).performClick(); 

        currentConversation = conversation;
        multiChatList = conversation.messages;

        llChatList.removeViewAt(0); 
        for(ChatMessage chatItem : multiChatList) { 
            if(chatItem.role == ChatRole.USER || (chatItem.role == ChatRole.ASSISTANT && chatItem.toolCalls.size() == 0)) {
                LinearLayout llChatItem = addChatView(chatItem.role, chatItem.contentText, chatItem.attachments);
                llChatItem.setTag(chatItem);
            }
        }
        scrollChatAreaToBottom();
    }

    private void clearChatListView() {
        if(currentCall != null && !currentCall.isCanceled()){
            currentCall.cancel();
        }
        llChatList.removeAllViews();
        ttsManager.stop();

        TextView tv = new TextView(this); 
        tv.setTextColor(Color.parseColor("#000000"));
        tv.setTextSize(16);
        tv.setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10));
        tv.setText(R.string.default_greeting);
        tvGptReply = tv;
        llChatList.addView(tv);
    }

    private void handleShareIntent(Intent intent) {
        if(intent != null){
            String action = intent.getAction();
            if(Intent.ACTION_PROCESS_TEXT.equals(action)) { 
                String text = intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT);
                if(text != null){
                    etUserInput.setText(text);
                }
            } else if(Intent.ACTION_SEND.equals(action)) { 
                String type = intent.getType();
                if(type != null && type.startsWith("image/")) {
                    Uri imageUri = intent.getParcelableExtra(Intent.EXTRA_STREAM); 
                    if (imageUri != null) {
                        addAttachment(imageUri); 
                    }
                    if (!GlobalUtils.checkVisionSupport(GlobalDataHolder.getGptModel()))
                        Toast.makeText(this, R.string.toast_use_vision_model, Toast.LENGTH_LONG).show();
                } else if(type != null && type.equals("text/plain") && intent.getStringExtra(Intent.EXTRA_TEXT) != null) { 
                    String text = intent.getStringExtra(Intent.EXTRA_TEXT);
                    if(text != null) {
                        etUserInput.setText(text);
                    }
                } else { 
                    Uri documentUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                    if(documentUri != null) {
                        addAttachment(documentUri); 
                    }
                }
            } else if(Intent.ACTION_VIEW.equals(action)) { 
                Uri documentUri = intent.getData();
                if(documentUri != null) {
                    addAttachment(documentUri); 
                }
            } else if(Intent.ACTION_SEND_MULTIPLE.equals(action)) { 
                ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                for(Uri uri : uris) {
                    if(uri != null) {
                        addAttachment(uri); 
                    }
                }
            }
        }
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    private Bitmap resizeBitmap(Bitmap bitmap, int maxWidth, int maxHeight) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        float scale = 1;
        if(width > maxWidth || height > maxHeight)
            scale = Math.min((float)maxWidth / width, (float)maxHeight / height);
        return Bitmap.createScaledBitmap(bitmap, (int)(width * scale), (int)(height * scale), true);
    }

    private Bitmap base64ToBitmap(String base64) {
        byte[] bytes = Base64.decode(base64, Base64.NO_WRAP);
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    private String bitmapToBase64(Bitmap bitmap) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, baos);
        byte[] bytes = baos.toByteArray();
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    public static boolean isAlive() {
        return isAlive;
    }

    public static boolean isRunning() {
        return isRunning;
    }

    private void requestPermission() {
        String[] permissions = {
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.INTERNET,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        };

        ArrayList<String> toApplyList = new ArrayList<String>();

        for (String perm : permissions) {
            if (PackageManager.PERMISSION_GRANTED != ContextCompat.checkSelfPermission(this, perm)) {
                toApplyList.add(perm);
            }
        }
        String[] tmpList = new String[toApplyList.size()];
        if (!toApplyList.isEmpty()) {
            requestPermissions(toApplyList.toArray(tmpList), 123);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShareIntent(intent);
    }

    void updateForMultiWindowMode() {
        if(isInMultiWindowMode()) { 
            findViewById(R.id.ll_main).setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else { 
            findViewById(R.id.ll_main).setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode, Configuration newConfig) {
        updateForMultiWindowMode();
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig);
    }

    @Override
    protected void onResume() {
        super.onResume();
        isRunning = true;
        Log.d("main activity", "back to main activity");
    }

    @Override
    protected void onPause() {
        super.onPause();
        isRunning = false;
        Log.d("main activity", "leave main activity");
    }

    @Override
    protected void onDestroy() {
        isAlive = false;
        LocalBroadcastManager.getInstance(this).unregisterReceiver(localReceiver);
        asrClient.destroy();
        ttsManager.shutdown(); 
        webScraper.destroy();
        if(((multiChatList.size() > 0 && multiChatList.get(0).role != ChatRole.SYSTEM) || (multiChatList.size() > 1 && multiChatList.get(0).role == ChatRole.SYSTEM)) &&
                GlobalDataHolder.getAutoSaveHistory()) 
            chatManager.addConversation(currentConversation);
        chatManager.removeEmptyConversations();
        chatManager.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        finish();
        super.onBackPressed();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.translate_up_in, R.anim.translate_down_out);
    }
}
