package com.atharva.ollama;

import androidx.lifecycle.ViewModelProvider;
import com.atharva.ollama.util.MarkdownHelper;
import android.content.Context;
import androidx.annotation.NonNull;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.util.TypedValue;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.MenuItem;
import android.widget.PopupMenu;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.activity.OnBackPressedCallback;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.atharva.ollama.adapter.ChatAdapter;
import com.atharva.ollama.adapter.ConversationAdapter;
import com.atharva.ollama.api.ApiProvider;
import com.atharva.ollama.api.UnifiedApiClient;
import com.atharva.ollama.database.AppDatabase;
import com.atharva.ollama.database.Conversation;
import com.atharva.ollama.database.ConversationDao;
import com.atharva.ollama.database.Message;
import com.atharva.ollama.database.MessageDao;
import com.atharva.ollama.model.ChatMessage;
import com.atharva.ollama.util.PreferencesManager;

import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import android.Manifest;
import android.content.pm.PackageManager;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.noties.markwon.Markwon;
import com.atharva.ollama.viewmodel.ChatViewModel;

/**
 * Main chat activity for Atharva AI.
 */
public class MainActivity extends AppCompatActivity implements ConversationAdapter.ConversationClickListener {

    // Member Variables
    private ChatAdapter chatAdapter;
    private ConversationAdapter conversationAdapter;
    private LinearLayoutManager layoutManager;
    private PreferencesManager prefs;
    private ChatViewModel viewModel;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private long currentConversationId = -1;
    private boolean isStreaming = false;
    private TextView txtModel;
    private View btnSettings;
    private ImageButton btnMenu;
    private ImageButton btnNewChat;
    private android.widget.LinearLayout btnNewChatDrawer;
    private SwitchCompat switchDarkMode;
    private ChipGroup suggestionChips;
    private ImageButton btnSearchFocus;
    private boolean isListening = false;
    private SpeechRecognizer speechRecognizer;
    private TextToSpeech textToSpeech;
    private View emptyState;
    private ConversationDao conversationDao;
    private MessageDao messageDao;
    private Markwon markwon;
    private ActivityResultLauncher<String> requestPermissionLauncher = 
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            if (isGranted) startVoiceInput();
        });

    // UI Components
    private DrawerLayout drawerLayout;
    private RecyclerView recyclerMessages;
    private RecyclerView recyclerConversations;
    private EditText editMessage;
    private ImageButton btnSend;
    private ImageButton btnStop;

    private ImageButton btnMic;
    private ImageButton btnAttach;
    private RecyclerView recyclerImages;
    private ImagePreviewAdapter imagePreviewAdapter;
    private final ActivityResultLauncher<String> pickMedia = registerForActivityResult(
            new ActivityResultContracts.GetMultipleContents(),
            uris -> {
                if (uris != null && !uris.isEmpty()) {
                    for (android.net.Uri uri : uris) {
                        // Persist permission (if needed, though GetContent usually grants temporary access)
                        // Ideally copy to app cache for persistence, but for now we use the URI string
                        // For robust implementation, we should copy the file.
                        // For this demo/fast implementation, assuming URI access is sufficient for the session.
                        // Better: Copy content to cache dir.
                        executor.execute(() -> {
                            String path = copyUriToCache(uri);
                            if (path != null) {
                                mainHandler.post(() -> viewModel.addPendingImage(path));
                            }
                        });
                    }
                }
            });

    // ...

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Initialize Preferences
        prefs = new PreferencesManager(this);
        
        // Apply Theme
        if (prefs.isDarkMode()) {
            setTheme(R.style.Theme_OllamaChat_Dark);
        } else {
            setTheme(R.style.Theme_OllamaChat);
        }

        setContentView(R.layout.activity_main);
        
        // Initialize Database
        AppDatabase db = AppDatabase.getInstance(this);
        conversationDao = db.conversationDao();
        messageDao = db.messageDao();

        // Initialize ViewModel (Using default AndroidViewModel factory)
        viewModel = new ViewModelProvider(this).get(ChatViewModel.class);

        // Initialize Markwon
        markwon = MarkdownHelper.createMarkwon(this, prefs.isDarkMode());

        initViews();
        initRecyclerViews();
        setupListeners();
        setupObservers(); // CRITICAL: Observe ViewModel LiveData
        
        // Setup System Bar Colors (Edge-to-Edge)
        // WindowCompat.setDecorFitsSystemWindows(getWindow(), false); // If using edge-to-edge
        
        loadOrCreateConversation();
    }

    private void initViews() {
        drawerLayout = findViewById(R.id.drawerLayout);
        txtModel = findViewById(R.id.txtModel);
        
        recyclerMessages = findViewById(R.id.recyclerMessages);
        recyclerConversations = findViewById(R.id.recyclerConversations);
        editMessage = findViewById(R.id.editMessage);
        
        btnSend = findViewById(R.id.btnSend);
        btnStop = findViewById(R.id.btnStop);
        btnMic = findViewById(R.id.btnMic);
        btnAttach = findViewById(R.id.btnAttach);
        
        btnSettings = findViewById(R.id.btnSettingsDrawer);
        btnMenu = findViewById(R.id.btnMenu);
        btnNewChat = findViewById(R.id.btnNewChat);
        btnNewChatDrawer = findViewById(R.id.btnNewChatDrawer);
        
        switchDarkMode = findViewById(R.id.switchDarkMode);
        emptyState = findViewById(R.id.emptyState);
        suggestionChips = findViewById(R.id.suggestionChips);
        btnSearchFocus = findViewById(R.id.btnSearchFocus);
        
        recyclerImages = findViewById(R.id.recyclerImages);
        
        // Setup Image Preview Recycler
        imagePreviewAdapter = new ImagePreviewAdapter();
        recyclerImages.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        recyclerImages.setAdapter(imagePreviewAdapter);

        updateModelDisplay();
        updateSearchFocusUI(); 
        switchDarkMode.setChecked(prefs.isDarkMode());
        
        btnStop.setVisibility(View.GONE);
    }
    
    private String copyUriToCache(android.net.Uri uri) {
        try {
            java.io.InputStream is = getContentResolver().openInputStream(uri);
            java.io.File cacheDir = new java.io.File(getCacheDir(), "chat_images");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            
            String fileName = "img_" + System.currentTimeMillis() + "_" + java.util.UUID.randomUUID().toString() + ".jpg";
            java.io.File file = new java.io.File(cacheDir, fileName);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
            
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
            fos.close();
            is.close();
            return file.getAbsolutePath();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    // ...

    private void setupObservers() {
        viewModel.getStreamingText().observe(this, text -> {
            boolean hasLoading = false;
            if (chatAdapter.getItemCount() > 0) {
                ChatMessage last = chatAdapter.getLastMessage();
                if (last.getType() == ChatMessage.Type.LOADING) {
                    hasLoading = true;
                }
            }

            if (text != null && !text.isEmpty()) {
                if (hasLoading) {
                    // Replace loading with assistant message
                    chatAdapter.removeLastMessage();
                    chatAdapter.addMessage(new ChatMessage(ChatMessage.Type.ASSISTANT, text));
                } else {
                    // Update existing assistant message
                    chatAdapter.updateLastMessage(text);
                }
                scrollToBottom();
            }
        });

        viewModel.getStatus().observe(this, status -> {
            if (status != null) {
                chatAdapter.updateLoadingStatus(status);
            }
        });

        viewModel.getIsStreaming().observe(this, isStreaming -> {
            this.isStreaming = isStreaming;
            updateButtonState(isStreaming);
            if (!isStreaming) {
                // If stopped and last message is loading, remove it
                ChatMessage last = chatAdapter.getLastMessage();
                if (last != null && last.getType() == ChatMessage.Type.LOADING) {
                    chatAdapter.removeLastMessage();
                }
            }
        });

        viewModel.getError().observe(this, error -> {
            if (error != null) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show();
            }
        });
        
        viewModel.getPendingImages().observe(this, images -> {
            imagePreviewAdapter.setImages(images);
            recyclerImages.setVisibility(images.isEmpty() ? View.GONE : View.VISIBLE);
        });
    }



    private int calculateInSampleSize(android.graphics.BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    private class ImagePreviewAdapter extends RecyclerView.Adapter<ImagePreviewAdapter.ViewHolder> {
        private final List<String> images = new ArrayList<>();
        
        void setImages(List<String> newImages) {
            images.clear();
            images.addAll(newImages);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            View view = android.view.LayoutInflater.from(parent.getContext()).inflate(R.layout.item_image_preview, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            String path = images.get(position);
            // Load image (using Glide or simple bitmap decoding for now. Using simple decoding to avoid adding Glide dep if not present)
            // Ideally use Glide/Coil. Assuming no image loading lib, strictly standard.
            // But usually we should have one. Let's use simple scaling.
            try {
                // Safe decoding with downsampling
                android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(path, options);
                
                // Calculate inSampleSize
                options.inSampleSize = calculateInSampleSize(options, 200, 200); // 200dp thumbnail
                
                // Decode with inSampleSize
                options.inJustDecodeBounds = false;
                android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(path, options);
                
                if (bitmap != null) {
                    holder.imgPreview.setImageBitmap(bitmap);
                } else {
                    holder.imgPreview.setBackgroundColor(android.graphics.Color.GRAY);
                }
            } catch (Throwable e) { // Catch OOM and other errors
                e.printStackTrace();
                holder.imgPreview.setBackgroundColor(android.graphics.Color.GRAY);
            }
            
            holder.btnRemove.setOnClickListener(v -> viewModel.removePendingImage(holder.getBindingAdapterPosition()));
        }

        @Override
        public int getItemCount() {
            return images.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView imgPreview;
            ImageButton btnRemove;

            ViewHolder(View itemView) {
                super(itemView);
                imgPreview = itemView.findViewById(R.id.imgPreview);
                btnRemove = itemView.findViewById(R.id.btnRemove);
            }
        }
    }

    private void updateModelDisplay() {
        ApiProvider provider = prefs.getApiProvider();
        String model = prefs.getModel();
        
        if (provider == ApiProvider.OLLAMA) {
            txtModel.setText(model);
        } else {
            txtModel.setText(provider.getDisplayName() + " • " + model);
        }
    }

    private void initRecyclerViews() {
        // Chat messages with improved layout manager
        chatAdapter = new ChatAdapter(this, markwon);
        chatAdapter.setDarkMode(prefs.isDarkMode());
        layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        recyclerMessages.setLayoutManager(layoutManager);
        recyclerMessages.setAdapter(chatAdapter);
        
        // Disable item animator to prevent flickering during streaming
        recyclerMessages.setItemAnimator(null);

        // Conversations
        conversationAdapter = new ConversationAdapter(this);
        recyclerConversations.setLayoutManager(new LinearLayoutManager(this));
        recyclerConversations.setAdapter(conversationAdapter);
    }

    private void setupListeners() {
        btnAttach.setOnClickListener(v -> pickMedia.launch("image/*"));
        btnSend.setOnClickListener(v -> sendMessage());
        
        // Stop generation button
        btnStop.setOnClickListener(v -> {
            performHapticFeedback(v);
            stopGeneration();
            performHapticFeedback(v);
            stopGeneration();
        });
        
        btnMic.setOnClickListener(v -> checkPermissionAndStartVoice());
        
        chatAdapter.setOnMessageClickListener(text -> {
             speakText(text);
        });

        btnSettings.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(this, SettingsActivity.class));
        });

        btnMenu.setOnClickListener(v -> {
            drawerLayout.openDrawer(GravityCompat.START);
        });

        btnNewChat.setOnClickListener(v -> createNewConversation());

        btnNewChatDrawer.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            createNewConversation();
        });
        
        btnSearchFocus.setOnClickListener(this::showSearchFocusMenu);

        switchDarkMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked != prefs.isDarkMode()) {
                prefs.setDarkMode(isChecked);
                recreate();
            }
        });

        editMessage.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }
            return false;
        });
        
        // Setup suggestion chip listeners
        setupSuggestionChips();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }
    
    /**
     * Setup click listeners for suggestion chips.
     */
    private void setupSuggestionChips() {
        Chip chipExplain = findViewById(R.id.chipExplain);
        Chip chipCode = findViewById(R.id.chipCode);
        Chip chipCreative = findViewById(R.id.chipCreative);
        Chip chipSummarize = findViewById(R.id.chipSummarize);
        Chip chipTranslate = findViewById(R.id.chipTranslate);
        Chip chipBrainstorm = findViewById(R.id.chipBrainstorm);
        
        View.OnClickListener chipListener = v -> {
            Chip chip = (Chip) v;
            editMessage.setText(chip.getText());
            editMessage.setSelection(editMessage.getText().length());
            editMessage.requestFocus();
        };
        
        if (chipExplain != null) chipExplain.setOnClickListener(chipListener);
        if (chipCode != null) chipCode.setOnClickListener(chipListener);
        if (chipCreative != null) chipCreative.setOnClickListener(chipListener);
        if (chipSummarize != null) chipSummarize.setOnClickListener(chipListener);
        if (chipTranslate != null) chipTranslate.setOnClickListener(chipListener);
        if (chipBrainstorm != null) chipBrainstorm.setOnClickListener(chipListener);
    }
    
    /**
     * Stop the current generation.
     */
    private void stopGeneration() {
        viewModel.cancelGeneration();
        Toast.makeText(this, R.string.generation_stopped, Toast.LENGTH_SHORT).show();
    }
    
    /**
     * Perform haptic feedback on a view.
     */
    private void performHapticFeedback(View view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vibratorManager = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vibratorManager != null) {
                Vibrator vibrator = vibratorManager.getDefaultVibrator();
                vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null) {
                vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    private void loadConversations() {
        executor.execute(() -> {
            List<Conversation> conversations = conversationDao.getAllConversations();
            mainHandler.post(() -> {
                conversationAdapter.setConversations(conversations);
                conversationAdapter.setSelectedConversationId(currentConversationId);
            });
        });
    }

    private void loadOrCreateConversation() {
        long savedId = prefs.getCurrentConversationId();
        if (savedId != -1) {
            loadConversation(savedId);
        } else {
            createNewConversation();
        }
    }

    private void loadConversation(long conversationId) {
        executor.execute(() -> {
            Conversation conversation = conversationDao.getConversationById(conversationId);
            if (conversation != null) {
                List<Message> messages = messageDao.getMessagesForConversation(conversationId);
                List<ChatMessage> chatMessages = new ArrayList<>();
                for (Message msg : messages) {
                    ChatMessage.Type type = msg.isUser() ? ChatMessage.Type.USER : ChatMessage.Type.ASSISTANT;
                    ChatMessage chatMsg = new ChatMessage(type, msg.content, msg.timestamp, msg.id);

                    // Restore Images
                    if (msg.imagePaths != null && !msg.imagePaths.isEmpty()) {
                        try {
                            Type listType = new TypeToken<List<String>>() {}.getType();
                            List<String> images = new Gson().fromJson(msg.imagePaths, listType);
                            chatMsg.setImages(images);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }

                    // Restore Sources
                    if (msg.sourcesJson != null && !msg.sourcesJson.isEmpty()) {
                        try {
                            Type listType = new TypeToken<List<ChatMessage.Source>>() {}.getType();
                            List<ChatMessage.Source> sources = new Gson().fromJson(msg.sourcesJson, listType);
                            chatMsg.setSources(sources);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }

                    chatMessages.add(chatMsg);
                }

                mainHandler.post(() -> {
                    currentConversationId = conversationId;
                    prefs.setCurrentConversationId(conversationId);
                    chatAdapter.setMessages(chatMessages);
                    conversationAdapter.setSelectedConversationId(conversationId);
                    updateEmptyState();
                    scrollToBottom();
                });
            } else {
                mainHandler.post(this::createNewConversation);
            }
        });
    }

    private void createNewConversation() {
        executor.execute(() -> {
            Conversation conversation = new Conversation("New Chat");
            long id = conversationDao.insert(conversation);

            mainHandler.post(() -> {
                currentConversationId = id;
                prefs.setCurrentConversationId(id);
                chatAdapter.clearMessages();
                updateEmptyState();
                loadConversations();
            });
        });
    }

    private void updateEmptyState() {
        emptyState.setVisibility(chatAdapter.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void scrollToBottom() {
        if (chatAdapter.getItemCount() > 0) {
            recyclerMessages.post(() -> {
                recyclerMessages.scrollToPosition(chatAdapter.getItemCount() - 1);
            });
        }
    }

    private void sendMessage() {
        if (isStreaming) return;

        String messageText = editMessage.getText().toString().trim();
        if (messageText.isEmpty()) {
            editMessage.setError(getString(R.string.error_empty_message));
            return;
        }

        // Prevent crashes caused by missing API configuration.
        if (!validateApiConfiguration()) {
            return;
        }
        
        // Haptic feedback on send
        performHapticFeedback(btnSend);

        emptyState.setVisibility(View.GONE);

        // Add user message to UI only (saving is handled by repository)
        ChatMessage userMessage = new ChatMessage(ChatMessage.Type.USER, messageText);
        chatAdapter.addMessage(userMessage);
        scrollToBottom();

        editMessage.setText("");

        // Add loading indicator (shows "Thinking...", "Searching...", "Generating..." stages)
        ChatMessage loadingMessage = new ChatMessage(ChatMessage.Type.LOADING, "");
        chatAdapter.addMessage(loadingMessage);
        scrollToBottom();

        isStreaming = true;
        updateButtonState(true);

        // Send message via ViewModel (which handles saving to database)
        viewModel.sendMessage(currentConversationId, messageText);
        
        // Refresh conversations list for title update
        mainHandler.postDelayed(this::loadConversations, 500);
    }

    private void updateButtonState(boolean isStreaming) {
        if (isStreaming) {
            btnSend.setVisibility(View.GONE);
            btnStop.setVisibility(View.VISIBLE);
            btnMic.setVisibility(View.GONE);
            btnAttach.setVisibility(View.GONE);
        } else {
            btnSend.setVisibility(View.VISIBLE);
            btnStop.setVisibility(View.GONE);
            btnMic.setVisibility(View.VISIBLE); // Or check empty text
            btnAttach.setVisibility(View.VISIBLE);
        }
    }

    private boolean validateApiConfiguration() {
        ApiProvider provider = prefs.getApiProvider();
        
        // MediaPipe doesn't need base URL or API key - just model path
        if (provider == ApiProvider.MEDIAPIPE) {
            String modelPath = prefs.getMediaPipeModelPath();
            if (modelPath == null || modelPath.trim().isEmpty()) {
                Toast.makeText(this, "Please set a model path in Settings", Toast.LENGTH_LONG).show();
                return false;
            }
            java.io.File modelFile = new java.io.File(modelPath);
            if (!modelFile.exists()) {
                Toast.makeText(this, "Model file not found. Please download or check path.", Toast.LENGTH_LONG).show();
                return false;
            }
            return true;
        }
        
        String baseUrl = prefs.getBaseUrl();
        String model = prefs.getModel();

        if (model == null || model.trim().isEmpty()) {
            Toast.makeText(this, "Please set a model in Settings", Toast.LENGTH_LONG).show();
            return false;
        }

        if (provider.requiresApiKey()) {
            String apiKey = prefs.getApiKey();
            if (apiKey == null || apiKey.trim().isEmpty()) {
                Toast.makeText(this, R.string.api_key_required, Toast.LENGTH_LONG).show();
                return false;
            }
        }

        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            Toast.makeText(this, R.string.base_url_required, Toast.LENGTH_LONG).show();
            return false;
        }

        return true;
    }

    // sendApiRequest removed - logic moved to ChatViewModel


    @Override
    public void onConversationClick(Conversation conversation) {
        drawerLayout.closeDrawer(GravityCompat.START);
        if (conversation.id != currentConversationId) {
            loadConversation(conversation.id);
        }
    }

    @Override
    public void onConversationDelete(Conversation conversation) {
        executor.execute(() -> {
            conversationDao.delete(conversation);
            mainHandler.post(() -> {
                loadConversations();
                if (conversation.id == currentConversationId) {
                    createNewConversation();
                }
            });
        });
    }

    private void showSearchFocusMenu(View v) {
        PopupMenu popup = new PopupMenu(this, v);
        
        // Add menu items programmatically with icons
        popup.getMenu().add(0, 0, 0, R.string.search_focus_all).setIcon(R.drawable.ic_globe);
        popup.getMenu().add(0, 1, 1, R.string.search_focus_reddit).setIcon(R.drawable.ic_fire);
        popup.getMenu().add(0, 2, 2, R.string.search_focus_stackoverflow).setIcon(R.drawable.ic_code_box);
        popup.getMenu().add(0, 3, 3, R.string.search_focus_academic).setIcon(R.drawable.ic_school);
        popup.getMenu().add(0, 4, 4, R.string.search_focus_youtube).setIcon(R.drawable.ic_play);
        popup.getMenu().add(0, 5, 5, R.string.search_focus_news).setIcon(R.drawable.ic_news);

        // Force show icons
        try {
            java.lang.reflect.Field field = popup.getClass().getDeclaredField("mPopup");
            field.setAccessible(true);
            Object menuPopupHelper = field.get(popup);
            Class<?> classPopupHelper = Class.forName(menuPopupHelper.getClass().getName());
            java.lang.reflect.Method setForceShowIcon = classPopupHelper.getMethod("setForceShowIcon", boolean.class);
            setForceShowIcon.invoke(menuPopupHelper, true);
        } catch (Exception e) {
            e.printStackTrace();
        }

        popup.setOnMenuItemClickListener(item -> {
            String focus = PreferencesManager.SEARCH_FOCUS_ALL;
            switch (item.getItemId()) {
                case 1: focus = PreferencesManager.SEARCH_FOCUS_REDDIT; break;
                case 2: focus = PreferencesManager.SEARCH_FOCUS_STACKOVERFLOW; break;
                case 3: focus = PreferencesManager.SEARCH_FOCUS_ACADEMIC; break;
                case 4: focus = PreferencesManager.SEARCH_FOCUS_YOUTUBE; break;
                case 5: focus = PreferencesManager.SEARCH_FOCUS_NEWS; break;
                case 0: default: focus = PreferencesManager.SEARCH_FOCUS_ALL; break;
            }
            prefs.setSearchFocus(focus);
            updateSearchFocusUI();
            return true;
        });
        popup.show();
    }

    private void updateSearchFocusUI() {
        if (btnSearchFocus == null) return;
        
        String focus = prefs.getSearchFocus();
        int colorAttr;
        if (PreferencesManager.SEARCH_FOCUS_ALL.equals(focus)) {
             // Variant color (default/inactive look)
             colorAttr = com.google.android.material.R.attr.colorOnSurfaceVariant;
        } else {
             // Primary color (active look)
             colorAttr = com.google.android.material.R.attr.colorPrimary;
        }
        
        android.util.TypedValue typedValue = new android.util.TypedValue();
        getTheme().resolveAttribute(colorAttr, typedValue, true);
        int color = typedValue.data;
        btnSearchFocus.setImageTintList(android.content.res.ColorStateList.valueOf(color));
    }

    // Voice Input Implementation
    private void checkPermissionAndStartVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceInput();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void startVoiceInput() {
        if (isListening) return;
        
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    Toast.makeText(MainActivity.this, "Listening...", Toast.LENGTH_SHORT).show();
                    isListening = true;
                    btnMic.setColorFilter(getColor(R.color.accent_mic_active)); // Visual cue
                }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {
                    isListening = false;
                     // Reset color
                    TypedValue typedValue = new TypedValue();
                    getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true);
                    btnMic.setColorFilter(typedValue.data);
                }
                @Override public void onError(int error) {
                    isListening = false;
                    String message = "Error listening";
                    if (error == SpeechRecognizer.ERROR_NO_MATCH) message = "Didn't catch that";
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                    
                     // Reset color
                    TypedValue typedValue = new TypedValue();
                    getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true);
                    btnMic.setColorFilter(typedValue.data);
                }
                @Override public void onResults(Bundle results) {
                    isListening = false;
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String text = matches.get(0);
                        editMessage.append(text + " ");
                        editMessage.setSelection(editMessage.getText().length());
                    }
                     // Reset color
                    TypedValue typedValue = new TypedValue();
                    getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true);
                    btnMic.setColorFilter(typedValue.data);
                }
                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });
        }
        
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechRecognizer.startListening(intent);
    }
    
    // TTS Implementation
    private void speakText(String text) {
        if (textToSpeech == null) {
            textToSpeech = new TextToSpeech(this, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
                } else {
                     Toast.makeText(this, "TTS Init Failed", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
             textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }
    
    @Override
    protected void onDestroy() {
        if (speechRecognizer != null) speechRecognizer.destroy();
        if (textToSpeech != null) textToSpeech.shutdown();
        super.onDestroy();
    }
}
