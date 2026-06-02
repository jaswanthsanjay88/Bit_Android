package com.bit.service;

interface IModelLoadCallback {
    void onSuccess();
    void onError(String message);
}