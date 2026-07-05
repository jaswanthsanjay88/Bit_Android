package com.bit.api.openai

import com.bit.api.Constants

class QwenProvider : BaseOpenAiProvider() {
    override val name: String = Constants.PROVIDER_QWEN
    override val defaultBaseUrl: String = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
}
