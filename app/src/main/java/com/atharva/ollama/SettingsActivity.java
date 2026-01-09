package com.atharva.ollama;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.biometric.BiometricManager;

import com.atharva.ollama.api.ApiProvider;
import com.atharva.ollama.api.RetrofitClient;
import com.atharva.ollama.util.ModelDownloadManager;
import com.atharva.ollama.util.NetworkScanner;
import com.atharva.ollama.util.PreferencesManager;

/**
 * Settings activity for configuring Atharva AI.
 */
public class SettingsActivity extends AppCompatActivity {

    // Views
    private Spinner spinnerProvider;
    private LinearLayout ollamaSettings;
    private LinearLayout customUrlSettings;
    private LinearLayout apiKeySettings;
    private LinearLayout popularModels;
    private LinearLayout searxSettings;
    private LinearLayout mediaPipeSettings;
    private EditText editServerIp;
    private EditText editPort;
    private EditText editCustomUrl;
    private EditText editApiKey;
    private EditText editModel;
    private EditText editSystemPrompt;
    private EditText editSearxHost;
    private EditText editMediaPipeModelPath;
    private TextView txtPopularModels;
    private Button btnTestConnection;
    private Button btnAutoDetect;
    private Button btnSave;
    private ImageButton btnBack;
    private TextView txtConnectionStatus;
    private SwitchCompat switchWebSearch;
    private SwitchCompat switchBiometric;

    private PreferencesManager prefs;
    private NetworkScanner networkScanner;
    private ApiProvider selectedProvider;
    
    // Download UI
    private EditText editHfToken;
    private Spinner spinnerGemmaModel;
    private Button btnDownloadModel;
    private LinearLayout downloadProgressContainer;
    private ProgressBar downloadProgress;
    private TextView txtDownloadStatus;
    private ModelDownloadManager downloadManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = new PreferencesManager(this);
        setTheme(prefs.getThemeResId());

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        networkScanner = new NetworkScanner(this);

        initViews();
        loadSettings();
        setupListeners();
    }

    private void initViews() {
        spinnerProvider = findViewById(R.id.spinnerProvider);
        ollamaSettings = findViewById(R.id.ollamaSettings);
        customUrlSettings = findViewById(R.id.customUrlSettings);
        apiKeySettings = findViewById(R.id.apiKeySettings);
        popularModels = findViewById(R.id.popularModels);
        searxSettings = findViewById(R.id.searxSettings);
        editServerIp = findViewById(R.id.editServerIp);
        editPort = findViewById(R.id.editPort);
        editCustomUrl = findViewById(R.id.editCustomUrl);
        editApiKey = findViewById(R.id.editApiKey);
        editModel = findViewById(R.id.editModel);
        editSystemPrompt = findViewById(R.id.editSystemPrompt);
        editSearxHost = findViewById(R.id.editSearxHost);
        txtPopularModels = findViewById(R.id.txtPopularModels);
        btnTestConnection = findViewById(R.id.btnTestConnection);
        btnAutoDetect = findViewById(R.id.btnAutoDetect);
        btnSave = findViewById(R.id.btnSave);
        btnBack = findViewById(R.id.btnBack);
        txtConnectionStatus = findViewById(R.id.txtConnectionStatus);
        switchWebSearch = findViewById(R.id.switchWebSearch);
        switchBiometric = findViewById(R.id.switchBiometric);
        mediaPipeSettings = findViewById(R.id.mediaPipeSettings);
        editMediaPipeModelPath = findViewById(R.id.editMediaPipeModelPath);
        
        // Download UI
        editHfToken = findViewById(R.id.editHfToken);
        spinnerGemmaModel = findViewById(R.id.spinnerGemmaModel);
        btnDownloadModel = findViewById(R.id.btnDownloadModel);
        downloadProgressContainer = findViewById(R.id.downloadProgressContainer);
        downloadProgress = findViewById(R.id.downloadProgress);
        txtDownloadStatus = findViewById(R.id.txtDownloadStatus);
        
        // Setup Gemma model spinner
        ArrayAdapter<String> modelAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                ModelDownloadManager.GemmaModel.getDisplayNames()
        );
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerGemmaModel.setAdapter(modelAdapter);
        
        downloadManager = new ModelDownloadManager(this);

        // Setup provider spinner
        ArrayAdapter<String> providerAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                ApiProvider.getDisplayNames()
        );
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerProvider.setAdapter(providerAdapter);
    }

    private void loadSettings() {
        selectedProvider = prefs.getApiProvider();
        spinnerProvider.setSelection(selectedProvider.ordinal());

        editServerIp.setText(prefs.getServerIp());
        editPort.setText(prefs.getPort());
        editCustomUrl.setText(prefs.getCustomBaseUrl());
        editApiKey.setText(prefs.getApiKey());
        editModel.setText(prefs.getModel());
        editSystemPrompt.setText(prefs.getSystemPrompt());
        
        boolean searchEnabled = prefs.isWebSearchEnabled();
        switchWebSearch.setChecked(searchEnabled);
        editSearxHost.setText(prefs.getSearxHost());
        searxSettings.setVisibility(searchEnabled ? View.VISIBLE : View.GONE);

        // Biometric settings
        setupBiometricToggle();
        
        // MediaPipe settings
        editMediaPipeModelPath.setText(prefs.getMediaPipeModelPath());
        editHfToken.setText(prefs.getHfToken());
        spinnerGemmaModel.setSelection(prefs.getSelectedModelIndex());

        updateProviderUI(selectedProvider);
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> finish());
        btnSave.setOnClickListener(v -> saveSettings());
        btnTestConnection.setOnClickListener(v -> testConnection());
        btnAutoDetect.setOnClickListener(v -> autoDetectServer());
        
        switchWebSearch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            searxSettings.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });
        
        btnDownloadModel.setOnClickListener(v -> startModelDownload());

        spinnerProvider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedProvider = ApiProvider.values()[position];
                updateProviderUI(selectedProvider);
                
                String defaultModel = prefs.getDefaultModelForProvider(selectedProvider);
                if (editModel.getText().toString().isEmpty()) {
                    editModel.setText(defaultModel);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void updateProviderUI(ApiProvider provider) {
        ollamaSettings.setVisibility(View.GONE);
        customUrlSettings.setVisibility(View.GONE);
        apiKeySettings.setVisibility(View.GONE);
        popularModels.setVisibility(View.GONE);
        mediaPipeSettings.setVisibility(View.GONE);

        switch (provider) {
            case OLLAMA:
                ollamaSettings.setVisibility(View.VISIBLE);
                break;

            case CUSTOM:
                customUrlSettings.setVisibility(View.VISIBLE);
                apiKeySettings.setVisibility(View.VISIBLE);
                break;

            case OPENAI:
                apiKeySettings.setVisibility(View.VISIBLE);
                popularModels.setVisibility(View.VISIBLE);
                txtPopularModels.setText("gpt-4o, gpt-4o-mini, gpt-4-turbo, gpt-3.5-turbo");
                break;

            case OPENROUTER:
                apiKeySettings.setVisibility(View.VISIBLE);
                popularModels.setVisibility(View.VISIBLE);
                txtPopularModels.setText("openai/gpt-4o, anthropic/claude-3.5-sonnet, meta-llama/llama-3.3-70b-instruct");
                break;

            case GROQ:
                apiKeySettings.setVisibility(View.VISIBLE);
                popularModels.setVisibility(View.VISIBLE);
                txtPopularModels.setText("llama-3.3-70b-versatile, mixtral-8x7b-32768, gemma2-9b-it");
                break;

            case TOGETHER:
                apiKeySettings.setVisibility(View.VISIBLE);
                popularModels.setVisibility(View.VISIBLE);
                txtPopularModels.setText("meta-llama/Llama-3.3-70B-Instruct-Turbo, mistralai/Mixtral-8x7B-Instruct-v0.1");
                break;

            case ANTHROPIC:
                apiKeySettings.setVisibility(View.VISIBLE);
                popularModels.setVisibility(View.VISIBLE);
                txtPopularModels.setText("claude-3-5-sonnet-20241022, claude-3-opus-20240229, claude-3-haiku-20240307");
                break;
                
            case MEDIAPIPE:
                mediaPipeSettings.setVisibility(View.VISIBLE);
                break;
        }
    }

    private void saveSettings() {
        if (selectedProvider == ApiProvider.OLLAMA) {
            String serverIp = editServerIp.getText().toString().trim();
            String port = editPort.getText().toString().trim();
            if (serverIp.isEmpty()) {
                editServerIp.setError("Required");
                return;
            }
            if (port.isEmpty()) {
                editPort.setError("Required");
                return;
            }
            prefs.setServerIp(serverIp);
            prefs.setPort(port);
        } else if (selectedProvider == ApiProvider.CUSTOM) {
            String customUrl = editCustomUrl.getText().toString().trim();
            if (customUrl.isEmpty()) {
                editCustomUrl.setError(getString(R.string.base_url_required));
                return;
            }
            prefs.setCustomBaseUrl(customUrl);
        } else if (selectedProvider == ApiProvider.MEDIAPIPE) {
            String modelPath = editMediaPipeModelPath.getText().toString().trim();
            if (modelPath.isEmpty()) {
                editMediaPipeModelPath.setError("Model path is required");
                return;
            }
            
            // Compatibility check removed as we moved to GGUF models on Ai-Core
            
            prefs.setMediaPipeModelPath(modelPath);
            prefs.setHfToken(editHfToken.getText().toString().trim());
            prefs.setSelectedModelIndex(spinnerGemmaModel.getSelectedItemPosition());
        }

        if (selectedProvider.requiresApiKey()) {
            String apiKey = editApiKey.getText().toString().trim();
            if (apiKey.isEmpty() && selectedProvider != ApiProvider.CUSTOM) {
                editApiKey.setError(getString(R.string.api_key_required));
                return;
            }
            prefs.setApiKey(apiKey);
        }

        prefs.setApiProvider(selectedProvider);
        prefs.setModel(editModel.getText().toString().trim());
        prefs.setSystemPrompt(editSystemPrompt.getText().toString().trim());
        
        // Save Web Search
        boolean webSearch = switchWebSearch.isChecked();
        prefs.setWebSearchEnabled(webSearch);
        if (webSearch) {
            String searxHost = editSearxHost.getText().toString().trim();
            if (searxHost.isEmpty()) {
                // Use default if empty or prompt user
                searxHost = PreferencesManager.DEFAULT_SEARX_HOST;
            }
            prefs.setSearxHost(searxHost);
        }

        RetrofitClient.reset();

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void setupBiometricToggle() {
        BiometricManager biometricManager = BiometricManager.from(this);
        int canAuthenticate = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG | 
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        );
        
        if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
            switchBiometric.setEnabled(true);
            switchBiometric.setChecked(prefs.isBiometricEnabled());
            switchBiometric.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.setBiometricEnabled(isChecked);
                if (isChecked) {
                    Toast.makeText(this, "Biometric lock enabled", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            // Biometric not available
            switchBiometric.setEnabled(false);
            switchBiometric.setChecked(false);
            prefs.setBiometricEnabled(false);
        }
    }

    private void testConnection() {
        String ip = editServerIp.getText().toString().trim();
        String portStr = editPort.getText().toString().trim();

        if (ip.isEmpty() || portStr.isEmpty()) {
            Toast.makeText(this, "Please enter IP and port", Toast.LENGTH_SHORT).show();
            return;
        }

        btnTestConnection.setEnabled(false);
        txtConnectionStatus.setVisibility(View.VISIBLE);
        txtConnectionStatus.setText("Testing connection...");

        int port = Integer.parseInt(portStr);
        networkScanner.testConnection(ip, port, success -> {
            btnTestConnection.setEnabled(true);
            if (success) {
                txtConnectionStatus.setText("✓ " + getString(R.string.connection_success));
            } else {
                txtConnectionStatus.setText("✗ " + getString(R.string.connection_failed));
            }
        });
    }

    private void autoDetectServer() {
        btnAutoDetect.setEnabled(false);
        txtConnectionStatus.setVisibility(View.VISIBLE);
        txtConnectionStatus.setText(getString(R.string.scanning_network));

        networkScanner.scanNetwork(new NetworkScanner.ScanCallback() {
            @Override
            public void onServerFound(String ip, int port) {
                editServerIp.setText(ip);
                editPort.setText(String.valueOf(port));
                txtConnectionStatus.setText("✓ " + getString(R.string.server_found) + " " + ip);
                btnAutoDetect.setEnabled(true);
            }

            @Override
            public void onScanProgress(int current, int total) {
                txtConnectionStatus.setText(getString(R.string.scanning_network) + " " + current + "/" + total);
            }

            @Override
            public void onScanComplete(boolean found) {
                btnAutoDetect.setEnabled(true);
                if (!found) {
                    txtConnectionStatus.setText("✗ " + getString(R.string.no_server_found));
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        networkScanner.stopScanning();
        if (downloadManager != null) {
            downloadManager.cancelDownload();
        }
    }
    
    private void startModelDownload() {
        String hfToken = editHfToken.getText().toString().trim();
        if (hfToken.isEmpty()) {
            editHfToken.setError("Token required for download");
            Toast.makeText(this, "Please enter your HuggingFace token", Toast.LENGTH_SHORT).show();
            return;
        }
        
        int selectedIndex = spinnerGemmaModel.getSelectedItemPosition();
        ModelDownloadManager.GemmaModel selectedModel = ModelDownloadManager.GemmaModel.values()[selectedIndex];
        
        // Check if already downloaded
        if (downloadManager.isModelDownloaded(selectedModel)) {
            String path = downloadManager.getModelPath(selectedModel);
            editMediaPipeModelPath.setText(path);
            Toast.makeText(this, "Model already downloaded!", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Save token
        prefs.setHfToken(hfToken);
        prefs.setSelectedModelIndex(selectedIndex);
        
        // Show progress UI
        downloadProgressContainer.setVisibility(View.VISIBLE);
        btnDownloadModel.setEnabled(false);
        btnDownloadModel.setText(R.string.downloading);
        downloadProgress.setProgress(0);
        txtDownloadStatus.setText("Starting download...");
        
        downloadManager.downloadModel(selectedModel, hfToken, new ModelDownloadManager.DownloadCallback() {
            @Override
            public void onProgress(int percentage, long downloadedBytes, long totalBytes) {
                downloadProgress.setProgress(percentage);
                String status = String.format("%d%% (%d MB / %d MB)", 
                        percentage, 
                        downloadedBytes / (1024 * 1024), 
                        totalBytes / (1024 * 1024));
                txtDownloadStatus.setText(status);
            }
            
            @Override
            public void onComplete(String filePath) {
                downloadProgressContainer.setVisibility(View.GONE);
                btnDownloadModel.setEnabled(true);
                btnDownloadModel.setText(R.string.download_model);
                
                // Auto-set the model path
                editMediaPipeModelPath.setText(filePath);
                prefs.setMediaPipeModelPath(filePath);
                
                Toast.makeText(SettingsActivity.this, R.string.download_complete, Toast.LENGTH_SHORT).show();
            }
            
            @Override
            public void onError(String error) {
                downloadProgressContainer.setVisibility(View.GONE);
                btnDownloadModel.setEnabled(true);
                btnDownloadModel.setText(R.string.download_model);
                
                Toast.makeText(SettingsActivity.this, error, Toast.LENGTH_LONG).show();
            }
        });
    }
}
