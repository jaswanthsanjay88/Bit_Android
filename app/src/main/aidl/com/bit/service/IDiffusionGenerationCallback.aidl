package com.bit.service;

interface IDiffusionGenerationCallback {
    void onProgress(float progress, int currentStep, int totalSteps, String intermediateImageBase64);
    void onComplete(String imageBase64, long seed, int width, int height);
    void onError(String message);
}