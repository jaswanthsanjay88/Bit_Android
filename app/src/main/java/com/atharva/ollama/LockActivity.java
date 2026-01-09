package com.atharva.ollama;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.atharva.ollama.util.PreferencesManager;
import com.google.android.material.button.MaterialButton;

import java.util.concurrent.Executor;

/**
 * Lock screen activity that requires biometric authentication to access the app.
 */
public class LockActivity extends AppCompatActivity {

    private PreferencesManager prefs;
    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = new PreferencesManager(this);
        
        // Apply theme
        setTheme(prefs.getThemeResId());
        
        super.onCreate(savedInstanceState);
        
        // If biometric is not enabled, go directly to MainActivity
        if (!prefs.isBiometricEnabled()) {
            launchMainActivity();
            return;
        }
        
        // Check if biometric is available
        BiometricManager biometricManager = BiometricManager.from(this);
        int canAuthenticate = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG | 
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        );
        
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            // Biometric not available, disable the setting and proceed
            prefs.setBiometricEnabled(false);
            launchMainActivity();
            return;
        }
        
        setContentView(R.layout.activity_lock);
        
        setupBiometricPrompt();
        
        MaterialButton btnUnlock = findViewById(R.id.btnUnlock);
        btnUnlock.setOnClickListener(v -> showBiometricPrompt());
        
        // Show prompt automatically on launch
        showBiometricPrompt();
    }

    private void setupBiometricPrompt() {
        Executor executor = ContextCompat.getMainExecutor(this);
        
        biometricPrompt = new BiometricPrompt(this, executor, 
            new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    launchMainActivity();
                }

                @Override
                public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED || 
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        // User cancelled, close the app
                        finishAffinity();
                    } else {
                        Toast.makeText(LockActivity.this, errString, Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onAuthenticationFailed() {
                    super.onAuthenticationFailed();
                    Toast.makeText(LockActivity.this, "Authentication failed", Toast.LENGTH_SHORT).show();
                }
            });
        
        promptInfo = new BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_title))
            .setSubtitle(getString(R.string.biometric_subtitle))
            .setNegativeButtonText(getString(R.string.biometric_cancel))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG | 
                BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
            .build();
    }

    private void showBiometricPrompt() {
        biometricPrompt.authenticate(promptInfo);
    }

    private void launchMainActivity() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }
}
